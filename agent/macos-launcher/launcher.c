// In-process JVM launcher for the RemoteBle agent on macOS.
//
// Plain `./gradlew :agent:jvmRun` (or any bare `java`) is killed with SIGABRT the
// moment the Blue-Falcon engine creates a CBCentralManager: macOS TCC requires the
// running process's main bundle to declare NSBluetoothAlwaysUsageDescription, and a
// JVM process has none. Editing the JDK Info.plist, embedding the plist in a section,
// or launching a .app directly from a shell all still abort — only an app started via
// LaunchServices (`open`) whose Contents/Info.plist carries the key is honored.
//
// This binary is that app's main executable. It carries the usage description in its
// own __TEXT,__info_plist section (and the bundle's Info.plist repeats it), then starts
// the JVM on a background thread via JNI so CoreBluetooth runs inside a process the
// system trusts. It dlopens libjvm.dylib by absolute path, so it has no JRE-home-relative
// layout requirement and can live in Contents/MacOS/.
//
// The main thread runs the menu bar status item (see MenuBar.swift) so it's visible at
// a glance whether the agent is running, without shelling out to `ps`. This file stays
// plain C (not Objective-C/Swift) because it does nothing but raw JNI struct/function-
// pointer calls, which plain C handles with zero interop ceremony.
//
//   env AGENT_LIBJVM = absolute path to libjvm.dylib
//   env AGENT_CP     = JVM class path
//   argv[1]          = main class in slash form (e.g. com/example/.../MainKt)
//   argv[2]          = port (also used to poll the local dashboard; defaults to 8080)
//   argv[2..]        = program args passed to main(String[])
#include <dlfcn.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <jni.h>

typedef jint (*CreateVM)(JavaVM **, void **, void *);

static int gArgc;
static char **gArgv;

// Implemented in MenuBar.swift; runs the Cocoa app (status item, menu, dashboard
// polling) on the calling thread until the process exits or Quit is chosen.
extern void agent_menu_run(const char *port);

static void *runJvm(void *arg) {
    (void)arg;
    const char *libjvm = getenv("AGENT_LIBJVM");
    const char *cp = getenv("AGENT_CP");
    if (!libjvm || !cp) { fprintf(stderr, "AGENT_LIBJVM and AGENT_CP must be set\n"); exit(2); }

    void *h = dlopen(libjvm, RTLD_NOW);
    if (!h) { fprintf(stderr, "dlopen(%s): %s\n", libjvm, dlerror()); exit(3); }
    CreateVM createVM = (CreateVM)dlsym(h, "JNI_CreateJavaVM");
    if (!createVM) { fprintf(stderr, "JNI_CreateJavaVM not found\n"); exit(3); }

    char *cpOpt = malloc(strlen(cp) + 32);
    sprintf(cpOpt, "-Djava.class.path=%s", cp);

    JavaVMOption opts[1];
    opts[0].optionString = cpOpt;
    JavaVMInitArgs vmArgs;
    vmArgs.version = JNI_VERSION_1_8;
    vmArgs.nOptions = 1;
    vmArgs.options = opts;
    vmArgs.ignoreUnrecognized = JNI_FALSE;

    JavaVM *jvm; JNIEnv *env;
    if (createVM(&jvm, (void **)&env, &vmArgs) != JNI_OK) {
        fprintf(stderr, "JNI_CreateJavaVM failed\n"); exit(4);
    }

    jclass cls = (*env)->FindClass(env, gArgv[1]);
    if (!cls) { (*env)->ExceptionDescribe(env); exit(5); }
    jmethodID mid = (*env)->GetStaticMethodID(env, cls, "main", "([Ljava/lang/String;)V");
    if (!mid) { (*env)->ExceptionDescribe(env); exit(5); }

    int n = gArgc - 2;
    jclass strCls = (*env)->FindClass(env, "java/lang/String");
    jobjectArray jargs = (*env)->NewObjectArray(env, n, strCls, NULL);
    for (int i = 0; i < n; i++) {
        (*env)->SetObjectArrayElement(env, jargs, i, (*env)->NewStringUTF(env, gArgv[i + 2]));
    }

    (*env)->CallStaticVoidMethod(env, cls, mid, jargs);
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionDescribe(env); }

    (*jvm)->DestroyJavaVM(jvm); // blocks until non-daemon threads finish
    // Reaching here means the agent's main() returned without the process already having
    // been torn down by a signal-driven System.exit() (see MenuBar.swift's quit action) —
    // treat it as exit.
    exit(0);
    return NULL;
}

int main(int argc, char **argv) {
    if (argc < 2) { fprintf(stderr, "usage: agent-launcher <main/Class> [args...]\n"); return 2; }
    gArgc = argc;
    gArgv = argv;
    const char *port = argc > 2 ? argv[2] : "8080";

    // The JVM runs on a background thread and the Cocoa menu bar app owns the main thread — the
    // required arrangement on macOS (AppKit is main-thread-only). Note this also means the main
    // run loop is now live; a BLE engine that created a CBCentralManager with a nil queue would
    // begin delivering its callbacks on this thread. The current engine uses its own dispatch
    // queue, so that's moot today — but keep it in mind if the engine is ever swapped.
    pthread_t jvmThread;
    if (pthread_create(&jvmThread, NULL, runJvm, NULL) != 0) {
        perror("pthread_create(jvm)");
        return 6;
    }
    pthread_detach(jvmThread);

    agent_menu_run(port); // blocks, running the menu bar app until Quit or process exit
    return 0;
}
