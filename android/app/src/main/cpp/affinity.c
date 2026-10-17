// Keeps every thread of this process on the CPUs in `mask` (research/19). Laya's ONNX Runtime pool threads
// otherwise drift onto the little cores, and a pass runs at the pace of its slowest thread. Re-applied
// before each pass: Android moving the app between cpusets resets thread affinity on this kernel.
#define _GNU_SOURCE
#include <dirent.h>
#include <jni.h>
#include <sched.h>
#include <stdlib.h>

JNIEXPORT jint JNICALL Java_dev_layatest_agent_Native_pinAllThreads(JNIEnv *env, jclass cls, jint mask) {
    cpu_set_t set;
    CPU_ZERO(&set);
    for (int i = 0; i < 31; i++) if (mask & (1 << i)) CPU_SET(i, &set);
    DIR *d = opendir("/proc/self/task");
    if (!d) return -1;
    int pinned = 0;
    for (struct dirent *e; (e = readdir(d)) != NULL;) {
        int tid = atoi(e->d_name);  // "." and ".." give 0
        if (tid > 0 && sched_setaffinity(tid, sizeof(set), &set) == 0) pinned++;
    }
    closedir(d);
    return pinned;
}
