/*
 * EGL entry points for libltw.so (arm64-v8a).
 *
 * LTW implements four EGL functions itself: eglGetProcAddress, eglCreateContext, eglDestroyContext and eglMakeCurrent.
 * SDL3 looks up each other EGL function it needs with dlsym on the library it is given (POJAVEXEC_EGL=libltw.so), and it
 * stops with "Could not load EGL library" when one of them is missing. This file exports all of them.
 *
 * Most of them are stubs: a stub jumps through a slot, and at load time each slot receives the system libEGL.so function
 * of the same name. The arguments and the return value pass through unchanged.
 *
 * A request for desktop OpenGL (EGL_OPENGL_API) is turned into OpenGL ES. SDL makes that request unless the game asks for
 * OpenGL ES, and Android drivers such as the Mali-G57 do not provide desktop OpenGL. LTW draws through OpenGL ES 3, so
 * eglChooseConfig offers ES 3 configs and the context is created as ES 3. Requests for OpenGL ES pass through unchanged.
 *
 * scripts/build-ltw.sh checks that every name in both lists below is exported from the built library.
 */

#include <EGL/egl.h>
#include <dlfcn.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>

#ifndef EGL_CONTEXT_MINOR_VERSION_KHR
#define EGL_CONTEXT_MINOR_VERSION_KHR 0x30FB
#endif
#ifndef EGL_OPENGL_ES3_BIT
#define EGL_OPENGL_ES3_BIT 0x00000040
#endif

/* Forwarded to the system EGL as they are */
#define LTW_EGL_PASSTHROUGH(X) \
    X(eglBindTexImage) \
    X(eglClientWaitSync) \
    X(eglCopyBuffers) \
    X(eglCreateImage) \
    X(eglCreatePbufferFromClientBuffer) \
    X(eglCreatePbufferSurface) \
    X(eglCreatePixmapSurface) \
    X(eglCreatePlatformPixmapSurface) \
    X(eglCreatePlatformWindowSurface) \
    X(eglCreateSync) \
    X(eglCreateWindowSurface) \
    X(eglDestroyImage) \
    X(eglDestroySurface) \
    X(eglDestroySync) \
    X(eglGetConfigAttrib) \
    X(eglGetConfigs) \
    X(eglGetCurrentContext) \
    X(eglGetCurrentDisplay) \
    X(eglGetCurrentSurface) \
    X(eglGetDisplay) \
    X(eglGetError) \
    X(eglGetPlatformDisplay) \
    X(eglGetSyncAttrib) \
    X(eglInitialize) \
    X(eglQueryAPI) \
    X(eglQueryContext) \
    X(eglQueryString) \
    X(eglQuerySurface) \
    X(eglReleaseTexImage) \
    X(eglReleaseThread) \
    X(eglSurfaceAttrib) \
    X(eglSwapBuffers) \
    X(eglSwapInterval) \
    X(eglTerminate) \
    X(eglWaitClient) \
    X(eglWaitGL) \
    X(eglWaitNative) \
    X(eglWaitSync)

/* These look at the request before it is forwarded */
#define LTW_EGL_TRANSLATED(X) \
    X(eglBindAPI) \
    X(eglChooseConfig)

#define LTW_EGL_ALL(X) LTW_EGL_PASSTHROUGH(X) LTW_EGL_TRANSLATED(X)

#define LTW_EGL_SLOT(name) __attribute__((visibility("hidden"), used)) void *ltw_egl_slot_##name;
LTW_EGL_ALL(LTW_EGL_SLOT)
#undef LTW_EGL_SLOT

/*
 * hint #34 is BTI c, which does nothing on CPUs without branch target identification. adrp and ldr read the slot, and br
 * jumps to it. x16 is the scratch register for veneers, so x0-x7 still hold the call arguments.
 */
#define LTW_EGL_STUB(name) \
    __asm__( \
        ".pushsection .text\n" \
        ".p2align 2\n" \
        ".globl " #name "\n" \
        ".type " #name ", %function\n" \
        #name ":\n" \
        "hint #34\n" \
        "adrp x16, ltw_egl_slot_" #name "\n" \
        "ldr x16, [x16, :lo12:ltw_egl_slot_" #name "]\n" \
        "br x16\n" \
        ".size " #name ", .-" #name "\n" \
        ".popsection\n");
LTW_EGL_PASSTHROUGH(LTW_EGL_STUB)
#undef LTW_EGL_STUB

/* Stands in for a function that the system EGL does not have. It returns 0, which is EGL_FALSE or a null handle. */
static intptr_t ltw_egl_missing(void) {
    return 0;
}

/* Set on the calling thread when its last eglBindAPI asked for desktop OpenGL */
static _Thread_local bool ltw_desktop_requested = false;

/* egl.c calls eglCreateContext through host_eglCreateContext. The hook below replaces that pointer after a desktop request. */
extern EGLContext (*host_eglCreateContext)(EGLDisplay dpy, EGLConfig config, EGLContext share_context, const EGLint *attrib_list);
static EGLContext (*ltw_host_create_context)(EGLDisplay, EGLConfig, EGLContext, const EGLint *) = NULL;
static bool ltw_create_hook_installed = false;

static EGLContext ltw_create_es_context(EGLDisplay dpy, EGLConfig config, EGLContext share_context, const EGLint *attrib_list) {
    if (!ltw_desktop_requested) return ltw_host_create_context(dpy, config, share_context, attrib_list);
    /* Newest first. The last attempt asks only for the client version, for drivers without EGL_KHR_create_context. */
    static const EGLint attempts[4][5] = {
            {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_CONTEXT_MINOR_VERSION_KHR, 2, EGL_NONE},
            {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_CONTEXT_MINOR_VERSION_KHR, 1, EGL_NONE},
            {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_CONTEXT_MINOR_VERSION_KHR, 0, EGL_NONE},
            {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE, 0, 0},
    };
    for (size_t i = 0; i < sizeof(attempts) / sizeof(attempts[0]); i++) {
        EGLContext ctx = ltw_host_create_context(dpy, config, share_context, attempts[i]);
        if (ctx != EGL_NO_CONTEXT) {
            printf("LTW: desktop OpenGL request, created an OpenGL ES 3 context (attempt %u)\n", (unsigned) i);
            return ctx;
        }
    }
    printf("LTW: desktop OpenGL request, no OpenGL ES 3 context could be created\n");
    return EGL_NO_CONTEXT;
}

static void ltw_install_create_context_hook(void) {
    if (ltw_create_hook_installed) return;
    ltw_host_create_context = host_eglCreateContext;
    host_eglCreateContext = ltw_create_es_context;
    ltw_create_hook_installed = true;
}

EGLAPI EGLBoolean EGLAPIENTRY eglBindAPI(EGLenum api) {
    ltw_desktop_requested = api == EGL_OPENGL_API;
    if (ltw_desktop_requested) {
        ltw_install_create_context_hook();
        api = EGL_OPENGL_ES_API;
    }
    return ((EGLBoolean (*)(EGLenum)) ltw_egl_slot_eglBindAPI)(api);
}

EGLAPI EGLBoolean EGLAPIENTRY eglChooseConfig(EGLDisplay dpy, const EGLint *attrib_list, EGLConfig *configs,
                                              EGLint config_size, EGLint *num_config) {
    typedef EGLBoolean (*choose_config_fn)(EGLDisplay, const EGLint *, EGLConfig *, EGLint, EGLint *);
    choose_config_fn host = (choose_config_fn) ltw_egl_slot_eglChooseConfig;
    if (!ltw_desktop_requested || attrib_list == NULL) {
        return host(dpy, attrib_list, configs, config_size, num_config);
    }

    EGLint attribs[64];
    int count = 0;
    for (const EGLint *p = attrib_list; p[0] != EGL_NONE; p += 2) {
        if (count + 3 > 64) return host(dpy, attrib_list, configs, config_size, num_config);
        EGLint value = p[1];
        if (p[0] == EGL_RENDERABLE_TYPE && (value & EGL_OPENGL_BIT)) {
            value = (value & ~EGL_OPENGL_BIT) | EGL_OPENGL_ES3_BIT | EGL_OPENGL_ES2_BIT;
        }
        attribs[count++] = p[0];
        attribs[count++] = value;
    }
    attribs[count] = EGL_NONE;
    return host(dpy, attribs, configs, config_size, num_config);
}

__attribute__((constructor, used)) static void ltw_egl_forward_init(void) {
    const char *eglPath = getenv("LIBGL_EGL");
    void *host = NULL;
    if (eglPath != NULL) host = dlopen(eglPath, RTLD_LAZY | RTLD_LOCAL);
    if (host == NULL) host = dlopen("libEGL.so", RTLD_LAZY | RTLD_LOCAL);
    if (host == NULL) printf("LTWInit: cannot load the system EGL: %s\n", dlerror());

    int resolved = 0;
#define LTW_EGL_RESOLVE(name) \
    ltw_egl_slot_##name = host != NULL ? dlsym(host, #name) : NULL; \
    if (ltw_egl_slot_##name != NULL) resolved++; \
    else ltw_egl_slot_##name = (void *) ltw_egl_missing;
    LTW_EGL_ALL(LTW_EGL_RESOLVE)
#undef LTW_EGL_RESOLVE
    printf("LTWInit: %d EGL functions forwarded to the system EGL\n", resolved);
}
