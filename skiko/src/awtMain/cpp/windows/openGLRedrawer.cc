#include <windows.h>
#include <wingdi.h>
#include <gl/GL.h>
#include <jawt_md.h>
#include <dwmapi.h>
#include "jni_helpers.h"
#include "exceptions_handler.h"
#include "window_util.h"

extern "C"
{
    JNIEXPORT jlong JNICALL Java_org_jetbrains_skiko_redrawer_WindowsOpenGLRedrawerKt_getDevice(JNIEnv *env, jobject redrawer, jlong platformInfoPtr)
    {
        __try
        {
            JAWT_Win32DrawingSurfaceInfo* dsi_win = fromJavaPointer<JAWT_Win32DrawingSurfaceInfo *>(platformInfoPtr);
            HWND hwnd = dsi_win->hwnd;
            HDC device = GetDC(hwnd);

            PIXELFORMATDESCRIPTOR pixFormatDscr;
            memset(&pixFormatDscr, 0, sizeof(PIXELFORMATDESCRIPTOR));
            pixFormatDscr.nSize = sizeof(PIXELFORMATDESCRIPTOR);
            pixFormatDscr.nVersion = 1;
            pixFormatDscr.dwFlags = PFD_DRAW_TO_WINDOW | PFD_SUPPORT_OPENGL | PFD_DOUBLEBUFFER | PFD_SUPPORT_COMPOSITION;

            pixFormatDscr.iPixelType = PFD_TYPE_RGBA;
            pixFormatDscr.cColorBits = 32;
            pixFormatDscr.cAlphaBits = 8;
            int iPixelFormat = ChoosePixelFormat(device, &pixFormatDscr);
            SetPixelFormat(device, iPixelFormat, &pixFormatDscr);
            DescribePixelFormat(device, iPixelFormat, sizeof(PIXELFORMATDESCRIPTOR), &pixFormatDscr);
      
            return toJavaPointer(device);
        }
        __except(EXCEPTION_EXECUTE_HANDLER) {
            auto code = GetExceptionCode();
            throwJavaRenderExceptionByExceptionCode(env, __FUNCTION__, code);
        }
        return (jlong) 0;
    }

    JNIEXPORT void JNICALL Java_org_jetbrains_skiko_redrawer_WindowsOpenGLRedrawerKt_setSwapInterval(JNIEnv *env, jobject redrawer, jint interval)
    {
        __try
        {
            typedef BOOL (WINAPI *PFNWGLSWAPINTERVALEXTPROC)(int interval);
            // according to [https://opengl.gpuinfo.org/listreports.php?extension=WGL_EXT_swap_control&option=not] (filter by OS=windows)
            // there a very few devices that doesn't support swap control
            static PFNWGLSWAPINTERVALEXTPROC wglSwapIntervalEXT = (PFNWGLSWAPINTERVALEXTPROC) wglGetProcAddress("wglSwapIntervalEXT");
            if (wglSwapIntervalEXT != NULL)
            {
                wglSwapIntervalEXT(interval);
            }
        }
        __except(EXCEPTION_EXECUTE_HANDLER) {
            auto code = GetExceptionCode();
            throwJavaRenderExceptionByExceptionCode(env, __FUNCTION__, code);
        }
    }

    JNIEXPORT void JNICALL Java_org_jetbrains_skiko_redrawer_WindowsOpenGLRedrawerKt_swapBuffers(JNIEnv *env, jobject redrawer, jlong devicePtr)
    {
        __try
        {
            HDC device = fromJavaPointer<HDC>(devicePtr);
            SwapBuffers(device);
        }
        __except(EXCEPTION_EXECUTE_HANDLER) {
            auto code = GetExceptionCode();
            throwJavaRenderExceptionByExceptionCode(env, __FUNCTION__, code);
        }
    }

    JNIEXPORT void JNICALL Java_org_jetbrains_skiko_redrawer_WindowsOpenGLRedrawerKt_makeCurrent(JNIEnv *env, jobject redrawer, jlong devicePtr, jlong contextPtr)
    {
        HDC device = fromJavaPointer<HDC>(devicePtr);
        HGLRC context = fromJavaPointer<HGLRC>(contextPtr);
        wglMakeCurrent(device, context);
    }

    JNIEXPORT jlong JNICALL Java_org_jetbrains_skiko_redrawer_WindowsOpenGLRedrawerKt_createContext(JNIEnv *env, jobject redrawer, jlong devicePtr, jlong contentHandle, jboolean transparency)
    {
        __try
        {
            if (transparency)
            {
                HWND parent = GetAncestor(fromJavaPointer<HWND>(contentHandle), GA_PARENT);
                enableTransparentWindow(parent);
            }

            HDC device = fromJavaPointer<HDC>(devicePtr);
            return toJavaPointer(wglCreateContext(device));
        }
        __except(EXCEPTION_EXECUTE_HANDLER) {
            auto code = GetExceptionCode();
            throwJavaRenderExceptionByExceptionCode(env, __FUNCTION__, code);
        }
        return (jlong) 0;
    }

    JNIEXPORT void JNICALL Java_org_jetbrains_skiko_redrawer_WindowsOpenGLRedrawerKt_deleteContext(JNIEnv *env, jobject redrawer, jlong contextPtr)
    {
        HGLRC context = fromJavaPointer<HGLRC>(contextPtr);
        wglDeleteContext(context);
    }

    JNIEXPORT void JNICALL Java_org_jetbrains_skiko_redrawer_WindowsOpenGLRedrawerKt_dwmFlush(JNIEnv *env, jobject redrawer)
    {
        DwmFlush();
    }

    // Writes the nanoseconds from now until the next vblank DWM composes at, and the refresh period,
    // into timing[0] and timing[1]. Returns false when DWM has no timing.
    JNIEXPORT jboolean JNICALL Java_org_jetbrains_skiko_redrawer_WindowsOpenGLRedrawerKt_dwmVblankTiming(JNIEnv *env, jobject redrawer, jlongArray timing)
    {
        DWM_TIMING_INFO info = {};
        info.cbSize = sizeof(info);
        if (FAILED(DwmGetCompositionTimingInfo(NULL, &info)) || info.qpcRefreshPeriod == 0) {
            return JNI_FALSE;
        }
        LARGE_INTEGER now, frequency;
        QueryPerformanceCounter(&now);
        QueryPerformanceFrequency(&frequency);
        LONGLONG period = (LONGLONG) info.qpcRefreshPeriod;
        LONGLONG next = (LONGLONG) info.qpcVBlank;
        if (next <= now.QuadPart) {
            next += ((now.QuadPart - next) / period + 1) * period;
        }
        jlong values[2] = {
            (jlong) ((double) (next - now.QuadPart) * 1e9 / (double) frequency.QuadPart),
            (jlong) ((double) period * 1e9 / (double) frequency.QuadPart),
        };
        env->SetLongArrayRegion(timing, 0, 2, values);
        return JNI_TRUE;
    }

#ifndef CREATE_WAITABLE_TIMER_HIGH_RESOLUTION
#define CREATE_WAITABLE_TIMER_HIGH_RESOLUTION 0x00000002
#endif

    // Sleeps with sub-millisecond precision. Sleep() and Java's parkNanos wake on the system tick,
    // 15.6 ms unless some process raised the timer resolution.
    JNIEXPORT void JNICALL Java_org_jetbrains_skiko_redrawer_WindowsOpenGLRedrawerKt_sleepPrecisely(JNIEnv *env, jobject redrawer, jlong nanos)
    {
        static thread_local HANDLE timer = CreateWaitableTimerExW(NULL, NULL, CREATE_WAITABLE_TIMER_HIGH_RESOLUTION, TIMER_ALL_ACCESS);
        if (timer != NULL) {
            LARGE_INTEGER due;
            due.QuadPart = -(nanos / 100); // relative, in 100 ns units
            if (SetWaitableTimer(timer, &due, 0, NULL, NULL, FALSE)) {
                WaitForSingleObject(timer, INFINITE);
                return;
            }
        }
        Sleep((DWORD) (nanos / 1000000));
    }
}