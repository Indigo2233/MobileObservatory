/* Android SDK loading/model-query smoke test. No camera is opened or controlled.
 * Build/run instructions: app/src/main/jniLibs/README.md.
 */
#include <dlfcn.h>
#include <stdio.h>
#include <string.h>
#include "toupcam.h"

int main(int argc, char** argv) {
    if (argc != 2) {
        fprintf(stderr, "Usage: %s /path/to/libtoupcam.so\n", argv[0]);
        return 2;
    }
    void* sdk = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
    if (!sdk) {
        fprintf(stderr, "dlopen failed: %s\n", dlerror());
        return 1;
    }
    const char* (*version)(void) = dlsym(sdk, "Toupcam_Version");
    const ToupcamModelV2* (*get_model)(unsigned short, unsigned short) =
        dlsym(sdk, "Toupcam_get_Model");
    if (!version || !get_model || strcmp(version(), "60.33015.20261024")) {
        fprintf(stderr, "SDK version or required exports mismatch\n");
        return 1;
    }
    printf("SDK %s loaded with RTLD_NOW\n", version());
    const struct { unsigned short pid; const char* name; } models[] = {
        {0x1697, "GPM585M"}, {0x1500, "GPM462M"}, {0x14ff, "GPM462C"}
    };
    for (unsigned i = 0; i < sizeof(models) / sizeof(models[0]); ++i) {
        const ToupcamModelV2* model = get_model(0x0547, models[i].pid);
        if (!model || !model->name || strcmp(model->name, models[i].name)) {
            fprintf(stderr, "Model mismatch for PID 0x%04x\n", models[i].pid);
            return 1;
        }
        printf("0547:%04x %s flags=0x%llx %ux%u %.2fx%.2f um\n",
               models[i].pid, model->name, model->flag,
               model->res[0].width, model->res[0].height, model->xpixsz, model->ypixsz);
        if (i == 0) {
            /* This SDK advertises RAW16 for GPM585M. Its sensor ADC specification
             * is separate; capture must query get_MaxBitDepth/get_RawFormat. */
            const unsigned long long required = TOUPCAM_FLAG_MONO | TOUPCAM_FLAG_RAW16 |
                TOUPCAM_FLAG_ROI_HARDWARE | TOUPCAM_FLAG_ST4;
            if ((model->flag & required) != required || model->preview == 0 ||
                model->res[0].width != 3840 || model->res[0].height != 2160 ||
                model->xpixsz < 2.89f || model->xpixsz > 2.91f ||
                model->ypixsz < 2.89f || model->ypixsz > 2.91f) {
                fprintf(stderr, "GPM585M capabilities mismatch\n");
                return 1;
            }
        }
    }
    if (get_model(0, 0) || get_model(0x0547, 0xffff)) {
        fprintf(stderr, "Unexpected model for an invalid USB identity\n");
        return 1;
    }
    dlclose(sdk);
    puts("PASS: SDK load and model queries; camera acquisition requires hardware testing.");
    return 0;
}
