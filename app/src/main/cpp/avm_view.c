/* Sends one of thirteen fixed AVM touches: 2D views, the 2D/3D toggle and 3D buttons. No other frame can leave this file. */
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <sys/ioctl.h>
#include <unistd.h>

#define CAN_DEVICE "/dev/cis_can"
#define CAN_SEND 1 /* The request qg_canbus_control(1, packet) issues in libqg_hal. */
#define AVM_TOUCH 0x1c
#define AVM_TOUCH_LENGTH 8
#define TOUCH_COUNT 13

/* Touch coordinates of the OEM buttons, encoded as (x<<5), x>>3, y, y>>8. */
static const unsigned char kTouches[TOUCH_COUNT][4] = {
    {0x00, 0x37, 0x32, 0x00}, /* front */
    {0x00, 0x1e, 0xba, 0x00}, /* left */
    {0x00, 0x37, 0x80, 0x02}, /* rear */
    {0x00, 0x4b, 0xba, 0x00}, /* right */
    {0x00, 0x0a, 0x2f, 0x01}, /* 2D/3D toggle */
    {0x00, 0x37, 0x64, 0x00}, /* 3D N */
    {0xe0, 0x47, 0xd0, 0x00}, /* 3D NE */
    {0x00, 0x4b, 0x6e, 0x01}, /* 3D E */
    {0x80, 0x48, 0x03, 0x02}, /* 3D SE */
    {0x00, 0x37, 0x78, 0x02}, /* 3D S */
    {0x20, 0x26, 0x04, 0x02}, /* 3D SW */
    {0x00, 0x23, 0x6e, 0x01}, /* 3D W */
    {0x40, 0x26, 0xd1, 0x00}, /* 3D NW */
};

JNIEXPORT jint JNICALL
Java_local_codex_voyahcameralab_AvmViewControl_nativeTouch(JNIEnv *env, jclass type, jint target, jboolean pressed) {
    (void)env;
    (void)type;
    if (target < 0 || target >= TOUCH_COUNT) return -EINVAL;
    const unsigned char *touch = kTouches[target];
    unsigned char packet[16] = {AVM_TOUCH, AVM_TOUCH_LENGTH, touch[0], touch[1], touch[2], touch[3], 0, pressed ? 1 : 3, 0, 0};
    int fd = open(CAN_DEVICE, O_RDWR | O_CLOEXEC);
    if (fd < 0) return -errno;
    int result = ioctl(fd, CAN_SEND, packet);
    int failure = result < 0 ? errno : 0;
    close(fd);
    return -failure;
}
