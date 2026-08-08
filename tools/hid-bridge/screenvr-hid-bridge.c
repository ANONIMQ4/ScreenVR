#include <CoreFoundation/CoreFoundation.h>
#include <IOKit/hid/IOHIDKeys.h>
#include <IOKit/hid/IOHIDUsageTables.h>
#include <IOKit/hidsystem/IOHIDUserDevice.h>
#include <dlfcn.h>
#include <math.h>
#include <mach/mach_time.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/time.h>
#include <unistd.h>

static volatile sig_atomic_t g_running = 1;

static void handle_signal(int signal_number) {
    (void)signal_number;
    g_running = 0;
}

static double now_seconds(void) {
    struct timeval tv;
    gettimeofday(&tv, NULL);
    return (double)tv.tv_sec + (double)tv.tv_usec / 1000000.0;
}

static CFNumberRef cf_number(int value) {
    return CFNumberCreate(kCFAllocatorDefault, kCFNumberIntType, &value);
}

static CFStringRef cf_string(const char *value) {
    return CFStringCreateWithCString(kCFAllocatorDefault, value, kCFStringEncodingUTF8);
}

static int16_t axis_from_unit(double value) {
    if (value > 1.0) {
        value = 1.0;
    } else if (value < -1.0) {
        value = -1.0;
    }
    return (int16_t)lrint(value * 32767.0);
}

static CFDataRef report_descriptor_data(void) {
    static const uint8_t descriptor[] = {
        0x05, 0x01,       // Usage Page (Generic Desktop)
        0x09, 0x04,       // Usage (Joystick)
        0xA1, 0x01,       // Collection (Application)
        0x15, 0x01,       //   Logical Minimum (1)
        0x25, 0x08,       //   Logical Maximum (8)
        0x35, 0x01,       //   Physical Minimum (1)
        0x45, 0x08,       //   Physical Maximum (8)
        0x75, 0x04,       //   Report Size (4)
        0x95, 0x01,       //   Report Count (1)
        0x65, 0x00,       //   Unit (None)
        0x09, 0x39,       //   Usage (Hat switch)
        0x81, 0x42,       //   Input (Data, Variable, Absolute, Null State)
        0x05, 0x09,       //   Usage Page (Button)
        0x19, 0x01,       //   Usage Minimum (Button 1)
        0x29, 0x04,       //   Usage Maximum (Button 4)
        0x15, 0x00,       //   Logical Minimum (0)
        0x25, 0x01,       //   Logical Maximum (1)
        0x75, 0x01,       //   Report Size (1)
        0x95, 0x04,       //   Report Count (4)
        0x81, 0x02,       //   Input (Data, Variable, Absolute)
        0x05, 0x01,       //   Usage Page (Generic Desktop)
        0x16, 0x01, 0x80, //   Logical Minimum (-32767)
        0x26, 0xFF, 0x7F, //   Logical Maximum (32767)
        0x75, 0x10,       //   Report Size (16)
        0x95, 0x06,       //   Report Count (6)
        0x09, 0x30,       //   Usage (X)
        0x09, 0x31,       //   Usage (Y)
        0x09, 0x32,       //   Usage (Z)
        0x09, 0x33,       //   Usage (Rx)
        0x09, 0x34,       //   Usage (Ry)
        0x09, 0x35,       //   Usage (Rz)
        0x81, 0x02,       //   Input (Data, Variable, Absolute)
        0xC0              // End Collection
    };
    return CFDataCreate(kCFAllocatorDefault, descriptor, sizeof(descriptor));
}

static IOHIDUserDeviceRef create_device(void) {
    CFMutableDictionaryRef properties = CFDictionaryCreateMutable(
        kCFAllocatorDefault,
        0,
        &kCFTypeDictionaryKeyCallBacks,
        &kCFTypeDictionaryValueCallBacks
    );
    if (!properties) {
        return NULL;
    }

    CFDataRef descriptor = report_descriptor_data();
    CFNumberRef vendor_id = cf_number(0x1209);
    CFNumberRef product_id = cf_number(0x5348);
    CFNumberRef version = cf_number(1);
    CFNumberRef usage_page = cf_number(kHIDPage_GenericDesktop);
    CFNumberRef usage = cf_number(kHIDUsage_GD_Joystick);
    CFStringRef product = cf_string("ScreenVR Head Tracker");
    CFStringRef manufacturer = cf_string("ScreenVR");
    CFStringRef serial = cf_string("screenvr-head-tracker-001");

    CFDictionarySetValue(properties, CFSTR(kIOHIDReportDescriptorKey), descriptor);
    CFDictionarySetValue(properties, CFSTR(kIOHIDVendorIDKey), vendor_id);
    CFDictionarySetValue(properties, CFSTR(kIOHIDProductIDKey), product_id);
    CFDictionarySetValue(properties, CFSTR(kIOHIDVersionNumberKey), version);
    CFDictionarySetValue(properties, CFSTR(kIOHIDPrimaryUsagePageKey), usage_page);
    CFDictionarySetValue(properties, CFSTR(kIOHIDPrimaryUsageKey), usage);
    CFDictionarySetValue(properties, CFSTR(kIOHIDProductKey), product);
    CFDictionarySetValue(properties, CFSTR(kIOHIDManufacturerKey), manufacturer);
    CFDictionarySetValue(properties, CFSTR(kIOHIDSerialNumberKey), serial);

    typedef IOHIDUserDeviceRef (*LegacyCreateFn)(CFAllocatorRef, CFDictionaryRef);
    LegacyCreateFn legacy_create = (LegacyCreateFn)dlsym(RTLD_DEFAULT, "IOHIDUserDeviceCreate");
    IOHIDUserDeviceRef device = NULL;
    if (legacy_create) {
        device = legacy_create(kCFAllocatorDefault, properties);
    }
    if (!device) {
        device = IOHIDUserDeviceCreateWithProperties(kCFAllocatorDefault, properties, 0);
    }

    CFRelease(serial);
    CFRelease(manufacturer);
    CFRelease(product);
    CFRelease(usage);
    CFRelease(usage_page);
    CFRelease(version);
    CFRelease(product_id);
    CFRelease(vendor_id);
    CFRelease(descriptor);
    CFRelease(properties);
    return device;
}

static void put_i16_le(uint8_t *report, size_t offset, int16_t value) {
    report[offset] = (uint8_t)(value & 0xff);
    report[offset + 1] = (uint8_t)(((uint16_t)value >> 8) & 0xff);
}

static IOReturn send_report(IOHIDUserDeviceRef device, double time_seconds) {
    uint8_t report[13];
    memset(report, 0, sizeof(report));
    report[0] = 0x08; // centered hat switch, no buttons.

    int16_t yaw = axis_from_unit(sin(time_seconds * 1.0) * 0.75);
    int16_t pitch = axis_from_unit(cos(time_seconds * 0.7) * 0.45);
    int16_t roll = axis_from_unit(sin(time_seconds * 0.4) * 0.20);

    put_i16_le(report, 1, yaw);
    put_i16_le(report, 3, pitch);
    put_i16_le(report, 5, 0);
    put_i16_le(report, 7, roll);
    put_i16_le(report, 9, 0);
    put_i16_le(report, 11, 0);

    return IOHIDUserDeviceHandleReportWithTimeStamp(device, mach_absolute_time(), report, sizeof(report));
}

int main(int argc, char **argv) {
    int fps = 120;
    if (argc > 1) {
        fps = atoi(argv[1]);
        if (fps < 1 || fps > 1000) {
            fprintf(stderr, "Usage: %s [fps 1..1000]\n", argv[0]);
            return 2;
        }
    }

    signal(SIGINT, handle_signal);
    signal(SIGTERM, handle_signal);

    IOHIDUserDeviceRef device = create_device();
    if (!device) {
        fprintf(
            stderr,
            "Failed to create IOHIDUserDevice. macOS may require the "
            "com.apple.developer.hid.virtual.device entitlement for this API.\n"
        );
        return 1;
    }
    IOHIDUserDeviceActivate(device);

    printf("ScreenVR virtual HID is running at %d Hz. Press Ctrl+C to stop.\n", fps);
    fflush(stdout);

    const useconds_t sleep_us = (useconds_t)(1000000 / fps);
    const double started = now_seconds();
    uint64_t frames = 0;
    double last_print = started;

    while (g_running) {
        double now = now_seconds();
        IOReturn result = send_report(device, now - started);
        if (result != kIOReturnSuccess) {
            fprintf(stderr, "IOHIDUserDeviceHandleReport failed: 0x%08x\n", result);
        }
        frames++;
        if (now - last_print >= 1.0) {
            printf("hid reports %llu/s\n", (unsigned long long)frames);
            fflush(stdout);
            frames = 0;
            last_print = now;
        }
        usleep(sleep_us);
    }

    CFRelease(device);
    printf("Stopped.\n");
    return 0;
}
