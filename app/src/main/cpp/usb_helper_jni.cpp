#include <jni.h>
#include <stdlib.h>
#include <unistd.h>
#include <string.h>
#include <stdio.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <dirent.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <errno.h>
#include <elf.h>
#include <android/log.h>
#include <link.h>

#define TAG "UsbHelperJni"

static FILE *g_logfile = nullptr;
static void log_to_file(const char *level, const char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    __android_log_vprint(
        (level[0] == 'E') ? ANDROID_LOG_ERROR : ANDROID_LOG_INFO, TAG, fmt, ap);
    va_end(ap);
    if (g_logfile) {
        va_start(ap, fmt);
        fprintf(g_logfile, "[%s] ", level);
        vfprintf(g_logfile, fmt, ap);
        fprintf(g_logfile, "\n");
        fflush(g_logfile);
        va_end(ap);
    }
}

#define LOGI(...) log_to_file("I", __VA_ARGS__)
#define LOGE(...) log_to_file("E", __VA_ARGS__)

#define MAX_FDS 4

static struct {
    int valid;
    uint8_t busnum;
    uint8_t devaddr;
    int fd;
} g_usb_fds[MAX_FDS];

static char g_fake_usb_dir[256];
static char g_fake_sysfs_dir[256];

static int find_registered_fd(const char *path) {
    int busnum = 0, devaddr = 0;
    if (sscanf(path, "/dev/bus/usb/%d/%d", &busnum, &devaddr) != 2)
        return -1;
    for (int i = 0; i < MAX_FDS; i++) {
        if (g_usb_fds[i].valid &&
            g_usb_fds[i].busnum == (uint8_t)busnum &&
            g_usb_fds[i].devaddr == (uint8_t)devaddr) {
            int duped = dup(g_usb_fds[i].fd);
            if (duped >= 0) {
                lseek(duped, 0, SEEK_SET);
                LOGI("Intercepted open(%s) -> fd=%d (duped from %d)",
                     path, duped, g_usb_fds[i].fd);
            }
            return duped;
        }
    }
    return -1;
}

/* --- PLT/GOT hook implementation --- */

typedef int (*open_func_t)(const char *, int, ...);
typedef DIR *(*opendir_func_t)(const char *);
typedef void *(*dlopen_func_t)(const char *, int);
typedef int (*stat_func_t)(const char *, struct stat *);
typedef int (*access_func_t)(const char *, int);
typedef struct dirent *(*readdir_func_t)(DIR *);
typedef int (*fstat_func_t)(int, struct stat *);
typedef FILE *(*fopen_func_t)(const char *, const char *);

static open_func_t g_real_open = nullptr;
static opendir_func_t g_real_opendir = nullptr;
static dlopen_func_t g_real_dlopen = nullptr;
static stat_func_t g_real_stat = nullptr;
static access_func_t g_real_access = nullptr;
static readdir_func_t g_real_readdir = nullptr;
static fstat_func_t g_real_fstat = nullptr;
static fopen_func_t g_real_fopen = nullptr;
static char g_native_lib_dir[512] = {0};

/* Track which DIR* corresponds to our fake directories for readdir logging */
#define MAX_TRACKED_DIRS 8
static struct {
    DIR *dir;
    char path[256];
} g_tracked_dirs[MAX_TRACKED_DIRS];

#define SYSFS_PREFIX "/sys/bus/usb/devices"
#define SYSFS_PREFIX_LEN 20
#define USBFS_PREFIX "/dev/bus/usb"
#define USBFS_PREFIX_LEN 12

static int hooked_open(const char *pathname, int flags, ...) {
    mode_t mode = 0;
    if (flags & O_CREAT) {
        va_list ap;
        va_start(ap, flags);
        mode = (mode_t)va_arg(ap, int);
        va_end(ap);
    }

    if (!pathname) return g_real_open(pathname, flags, mode);

    LOGI("OPEN: %s flags=0x%x", pathname, flags);

    /* Redirect /dev/bus/usb/BBB/DDD â†?registered fd */
    if (strncmp(pathname, USBFS_PREFIX "/", USBFS_PREFIX_LEN + 1) == 0) {
        int fd = find_registered_fd(pathname);
        if (fd >= 0) return fd;
        LOGI("hooked_open: %s - no registered fd, falling through", pathname);
    }

    /* Redirect /sys/bus/usb/devices/... reads to fake sysfs */
    if (strncmp(pathname, SYSFS_PREFIX "/", SYSFS_PREFIX_LEN + 1) == 0 &&
        g_fake_sysfs_dir[0]) {
        char fake_path[512];
        snprintf(fake_path, sizeof(fake_path), "%s%s",
                 g_fake_sysfs_dir, pathname + SYSFS_PREFIX_LEN);
        LOGI("Redirecting open(%s) -> %s", pathname, fake_path);
        int fd = g_real_open(fake_path, flags, mode);
        if (fd >= 0) return fd;
        LOGI("Fake sysfs open failed (%s), falling through", strerror(errno));
    }

    return g_real_open(pathname, flags, mode);
}

static void track_dir(DIR *d, const char *original_path) {
    if (!d) return;
    for (int i = 0; i < MAX_TRACKED_DIRS; i++) {
        if (!g_tracked_dirs[i].dir) {
            g_tracked_dirs[i].dir = d;
            snprintf(g_tracked_dirs[i].path, sizeof(g_tracked_dirs[i].path), "%s", original_path);
            return;
        }
    }
}

static const char *find_tracked_dir(DIR *d) {
    for (int i = 0; i < MAX_TRACKED_DIRS; i++) {
        if (g_tracked_dirs[i].dir == d)
            return g_tracked_dirs[i].path;
    }
    return nullptr;
}

static DIR *hooked_opendir(const char *name) {
    if (!name) return g_real_opendir(name);

    LOGI("OPENDIR: %s", name);

    /* Redirect /sys/bus/usb/devices to fake sysfs dir */
    if (strncmp(name, SYSFS_PREFIX, SYSFS_PREFIX_LEN) == 0 && g_fake_sysfs_dir[0]) {
        char fake_path[512];
        if (name[SYSFS_PREFIX_LEN] == '\0')
            snprintf(fake_path, sizeof(fake_path), "%s", g_fake_sysfs_dir);
        else
            snprintf(fake_path, sizeof(fake_path), "%s%s",
                     g_fake_sysfs_dir, name + SYSFS_PREFIX_LEN);
        LOGI("Redirecting opendir(%s) -> %s", name, fake_path);
        DIR *d = g_real_opendir(fake_path);
        if (d) track_dir(d, name);
        else LOGE("opendir(%s) -> %s failed: %s", name, fake_path, strerror(errno));
        return d;
    }

    /* Redirect /dev/bus/usb/... to fake usbfs dir */
    if (strncmp(name, USBFS_PREFIX, USBFS_PREFIX_LEN) == 0 && g_fake_usb_dir[0]) {
        char fake_path[512];
        if (name[USBFS_PREFIX_LEN] == '\0')
            snprintf(fake_path, sizeof(fake_path), "%s", g_fake_usb_dir);
        else
            snprintf(fake_path, sizeof(fake_path), "%s%s",
                     g_fake_usb_dir, name + USBFS_PREFIX_LEN);
        LOGI("Redirecting opendir(%s) -> %s", name, fake_path);
        DIR *d = g_real_opendir(fake_path);
        if (d) track_dir(d, name);
        else LOGE("opendir(%s) -> %s failed: %s", name, fake_path, strerror(errno));
        return d;
    }

    return g_real_opendir(name);
}

static int hooked_stat(const char *path, struct stat *buf) {
    if (!path) return g_real_stat(path, buf);

    LOGI("STAT: %s", path);

    /* Redirect stat(/sys/bus/usb/devices) and children to fake sysfs */
    if (strcmp(path, SYSFS_PREFIX) == 0 && g_fake_sysfs_dir[0]) {
        LOGI("Redirecting stat(%s) -> %s", path, g_fake_sysfs_dir);
        return g_real_stat(g_fake_sysfs_dir, buf);
    }
    if (strncmp(path, SYSFS_PREFIX "/", SYSFS_PREFIX_LEN + 1) == 0 &&
        g_fake_sysfs_dir[0]) {
        char fake_path[512];
        snprintf(fake_path, sizeof(fake_path), "%s%s",
                 g_fake_sysfs_dir, path + SYSFS_PREFIX_LEN);
        LOGI("Redirecting stat(%s) -> %s", path, fake_path);
        return g_real_stat(fake_path, buf);
    }

    /* Redirect stat on /dev/bus/usb/... */
    if (strncmp(path, USBFS_PREFIX, USBFS_PREFIX_LEN) == 0 && g_fake_usb_dir[0]) {
        char fake_path[512];
        if (path[USBFS_PREFIX_LEN] == '\0')
            snprintf(fake_path, sizeof(fake_path), "%s", g_fake_usb_dir);
        else
            snprintf(fake_path, sizeof(fake_path), "%s%s",
                     g_fake_usb_dir, path + USBFS_PREFIX_LEN);
        LOGI("Redirecting stat(%s) -> %s", path, fake_path);
        return g_real_stat(fake_path, buf);
    }

    return g_real_stat(path, buf);
}

static int hooked_access(const char *path, int amode) {
    if (!path) return g_real_access(path, amode);

    if (strncmp(path, SYSFS_PREFIX, SYSFS_PREFIX_LEN) == 0 && g_fake_sysfs_dir[0]) {
        char fake_path[512];
        if (path[SYSFS_PREFIX_LEN] == '\0')
            snprintf(fake_path, sizeof(fake_path), "%s", g_fake_sysfs_dir);
        else
            snprintf(fake_path, sizeof(fake_path), "%s%s",
                     g_fake_sysfs_dir, path + SYSFS_PREFIX_LEN);
        LOGI("Redirecting access(%s) -> %s", path, fake_path);
        return g_real_access(fake_path, amode);
    }

    if (strncmp(path, USBFS_PREFIX, USBFS_PREFIX_LEN) == 0 && g_fake_usb_dir[0]) {
        char fake_path[512];
        if (path[USBFS_PREFIX_LEN] == '\0')
            snprintf(fake_path, sizeof(fake_path), "%s", g_fake_usb_dir);
        else
            snprintf(fake_path, sizeof(fake_path), "%s%s",
                     g_fake_usb_dir, path + USBFS_PREFIX_LEN);
        return g_real_access(fake_path, amode);
    }

    return g_real_access(path, amode);
}

static struct dirent *hooked_readdir(DIR *dirp) {
    struct dirent *entry = g_real_readdir(dirp);
    const char *tracked = find_tracked_dir(dirp);
    if (entry) {
        LOGI("READDIR(%s) -> '%s' d_type=%d", tracked ? tracked : "?", entry->d_name, entry->d_type);
    } else {
        LOGI("READDIR(%s) -> NULL (end)", tracked ? tracked : "?");
    }
    return entry;
}

static int hooked_fstat(int fd, struct stat *buf) {
    int ret = g_real_fstat(fd, buf);
    for (int i = 0; i < MAX_FDS; i++) {
        if (g_usb_fds[i].valid && g_usb_fds[i].fd == fd) {
            LOGI("fstat(fd=%d [registered USB]) -> ret=%d", fd, ret);
        }
    }
    return ret;
}

typedef FILE *(*popen_func_t)(const char *, const char *);
static popen_func_t g_real_popen = nullptr;

static FILE *hooked_popen(const char *command, const char *mode) {
    if (command) {
        LOGI("POPEN: '%s' mode=%s", command, mode);
    }
    return g_real_popen(command, mode);
}

static FILE *hooked_fopen(const char *pathname, const char *mode) {
    if (pathname) {
        LOGI("FOPEN: %s mode=%s", pathname, mode);

        /* Redirect sysfs fopen */
        if (strncmp(pathname, SYSFS_PREFIX "/", SYSFS_PREFIX_LEN + 1) == 0 &&
            g_fake_sysfs_dir[0]) {
            char fake_path[512];
            snprintf(fake_path, sizeof(fake_path), "%s%s",
                     g_fake_sysfs_dir, pathname + SYSFS_PREFIX_LEN);
            LOGI("Redirecting fopen(%s) -> %s", pathname, fake_path);
            FILE *f = g_real_fopen(fake_path, mode);
            if (f) return f;
        }
    }
    return g_real_fopen(pathname, mode);
}

struct plt_hook_entry {
    const char *symbol;
    void *new_func;
    void **orig_func;
};

static int hook_plt(const char *lib_name, struct plt_hook_entry *entries, int count);

static void install_hooks_in_tl(const char *tl_name) {
    struct plt_hook_entry usb_entries[] = {
        { "open",    (void *)hooked_open,    (void **)&g_real_open },
        { "opendir", (void *)hooked_opendir, (void **)&g_real_opendir },
        { "stat",    (void *)hooked_stat,    (void **)&g_real_stat },
        { "access",  (void *)hooked_access,  (void **)&g_real_access },
        { "readdir", (void *)hooked_readdir, (void **)&g_real_readdir },
        { "fstat",   (void *)hooked_fstat,   (void **)&g_real_fstat },
        { "fopen",   (void *)hooked_fopen,   (void **)&g_real_fopen },
        { "popen",   (void *)hooked_popen,   (void **)&g_real_popen },
    };
    int h = hook_plt(tl_name, usb_entries, 8);
    LOGI("Installed %d hooks in %s (from dlopen)", h, tl_name);
}

static void *hooked_dlopen(const char *filename, int flags) {
    if (filename && g_native_lib_dir[0]) {
        if (strstr(filename, "MvUsb3vTL") || strstr(filename, "MVGigEVisionSDK")) {
            const char *base = strrchr(filename, '/');
            base = base ? base + 1 : filename;
            char fullpath[1024];
            snprintf(fullpath, sizeof(fullpath), "%s/%s", g_native_lib_dir, base);
            LOGI("Redirecting dlopen(%s) -> %s", filename, fullpath);
            void *h = g_real_dlopen(fullpath, flags);
            if (h) {
                /* Install filesystem hooks in the newly loaded TL immediately,
                 * BEFORE it gets a chance to initialize libusb. */
                install_hooks_in_tl(base);
            }
            return h;
        }
    }
    return g_real_dlopen(filename, flags);
}

/* --- PLT/GOT hooking engine --- */

struct hook_ctx {
    const char *lib_name;
    struct plt_hook_entry *entries;
    int count;
    int hooked;
};

static int phdr_callback(struct dl_phdr_info *info, size_t, void *data) {
    auto *ctx = (struct hook_ctx *)data;
    if (!info->dlpi_name || !info->dlpi_name[0])
        return 0;

    if (!g_native_lib_dir[0] && strstr(info->dlpi_name, "/lib/arm64/lib")) {
        const char *last_slash = strrchr(info->dlpi_name, '/');
        if (last_slash) {
            size_t dir_len = last_slash - info->dlpi_name;
            if (dir_len < sizeof(g_native_lib_dir)) {
                strncpy(g_native_lib_dir, info->dlpi_name, dir_len);
                g_native_lib_dir[dir_len] = 0;
            }
        }
    }

    if (!strstr(info->dlpi_name, ctx->lib_name))
        return 0;

    uintptr_t base = info->dlpi_addr;
    LOGI("Found %s at base %p", ctx->lib_name, (void *)base);

    ElfW(Dyn) *dyn_section = nullptr;
    for (int i = 0; i < info->dlpi_phnum; i++) {
        if (info->dlpi_phdr[i].p_type == PT_DYNAMIC) {
            dyn_section = (ElfW(Dyn) *)(base + info->dlpi_phdr[i].p_vaddr);
            break;
        }
    }
    if (!dyn_section) return 0;

    ElfW(Sym) *symtab = nullptr;
    const char *strtab = nullptr;
    ElfW(Rela) *rela_plt = nullptr;
    size_t rela_plt_size = 0;

    for (ElfW(Dyn) *d = dyn_section; d->d_tag != DT_NULL; d++) {
        switch (d->d_tag) {
            case DT_SYMTAB:   symtab = (ElfW(Sym) *)d->d_un.d_ptr; break;
            case DT_STRTAB:   strtab = (const char *)d->d_un.d_ptr; break;
            case DT_JMPREL:   rela_plt = (ElfW(Rela) *)d->d_un.d_ptr; break;
            case DT_PLTRELSZ: rela_plt_size = d->d_un.d_val; break;
        }
    }

    if (symtab && (uintptr_t)symtab < base)
        symtab = (ElfW(Sym) *)(base + (uintptr_t)symtab);
    if (strtab && (uintptr_t)strtab < base)
        strtab = (const char *)(base + (uintptr_t)strtab);
    if (rela_plt && (uintptr_t)rela_plt < base)
        rela_plt = (ElfW(Rela) *)(base + (uintptr_t)rela_plt);

    if (!symtab || !strtab || !rela_plt || !rela_plt_size) return 0;

    size_t rela_count = rela_plt_size / sizeof(ElfW(Rela));
    for (size_t i = 0; i < rela_count; i++) {
        uint32_t sym_idx = ELF64_R_SYM(rela_plt[i].r_info);
        if (!sym_idx) continue;

        const char *sym_name = strtab + symtab[sym_idx].st_name;

        for (int j = 0; j < ctx->count; j++) {
            if (strcmp(sym_name, ctx->entries[j].symbol) != 0) continue;

            void **got_entry = (void **)(base + rela_plt[i].r_offset);
            if (ctx->entries[j].orig_func)
                *ctx->entries[j].orig_func = *got_entry;

            size_t page_size = sysconf(_SC_PAGESIZE);
            void *page_start = (void *)((uintptr_t)got_entry & ~(page_size - 1));
            if (mprotect(page_start, page_size, PROT_READ | PROT_WRITE) == 0) {
                *got_entry = ctx->entries[j].new_func;
                LOGI("Hooked %s in %s", sym_name, ctx->lib_name);
                ctx->hooked++;
            }
            break;
        }
    }
    return 0;
}

static int hook_plt(const char *lib_name, struct plt_hook_entry *entries, int count) {
    struct hook_ctx ctx = { lib_name, entries, count, 0 };
    dl_iterate_phdr(phdr_callback, &ctx);
    return ctx.hooked;
}

/* --- Helper functions --- */

static void mkdirs(const char *path) {
    char tmp[512];
    snprintf(tmp, sizeof(tmp), "%s", path);
    for (char *p = tmp + 1; *p; p++) {
        if (*p == '/') { *p = 0; mkdir(tmp, 0755); *p = '/'; }
    }
    mkdir(tmp, 0755);
}

static void write_file(const char *path, const char *content) {
    FILE *f = fopen(path, "w");
    if (f) { fputs(content, f); fclose(f); }
}

/* --- JNI entry points --- */

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_indigo_mobileobservatory_camera_UsbHelper_nativeRegisterUsbFd(
        JNIEnv *env, jclass, jstring jCacheDir,
        jint busNum, jint devAddr, jint fd) {

    const char *cacheDir = env->GetStringUTFChars(jCacheDir, nullptr);

    if (!g_logfile) {
        char logpath[512];
        snprintf(logpath, sizeof(logpath), "%s/usb_helper.log", cacheDir);
        g_logfile = fopen(logpath, "w");
    }

    for (int i = 0; i < MAX_FDS; i++) {
        if (!g_usb_fds[i].valid) {
            g_usb_fds[i].valid = 1;
            g_usb_fds[i].busnum = (uint8_t)busNum;
            g_usb_fds[i].devaddr = (uint8_t)devAddr;
            g_usb_fds[i].fd = fd;
            break;
        }
    }

    /* Create fake usbfs structure: fake_usb/BBB/DDD â†?/proc/self/fd/N */
    snprintf(g_fake_usb_dir, sizeof(g_fake_usb_dir), "%s/fake_usb", cacheDir);
    char busDir[512], devLink[512], fdTarget[64];
    snprintf(busDir, sizeof(busDir), "%s/%03d", g_fake_usb_dir, busNum);
    snprintf(devLink, sizeof(devLink), "%s/%03d", busDir, devAddr);
    snprintf(fdTarget, sizeof(fdTarget), "/proc/self/fd/%d", fd);

    mkdirs(busDir);
    unlink(devLink);
    symlink(fdTarget, devLink);

    /* Create fake sysfs structure for libusb's sysfs scanning:
     * fake_sysfs/<bus>-<dev>/busnum  â†?"<busnum>\n"
     * fake_sysfs/<bus>-<dev>/devnum  â†?"<devnum>\n"
     * fake_sysfs/<bus>-<dev>/speed   â†?"480\n"  (High-speed USB)
     */
    snprintf(g_fake_sysfs_dir, sizeof(g_fake_sysfs_dir), "%s/fake_sysfs", cacheDir);
    char devDir[512], filePath[512], content[32];

    snprintf(devDir, sizeof(devDir), "%s/%d-%d", g_fake_sysfs_dir, busNum, devAddr);
    mkdirs(devDir);

    snprintf(filePath, sizeof(filePath), "%s/busnum", devDir);
    snprintf(content, sizeof(content), "%d\n", busNum);
    write_file(filePath, content);

    snprintf(filePath, sizeof(filePath), "%s/devnum", devDir);
    snprintf(content, sizeof(content), "%d\n", devAddr);
    write_file(filePath, content);

    snprintf(filePath, sizeof(filePath), "%s/speed", devDir);
    write_file(filePath, "480\n");

    snprintf(filePath, sizeof(filePath), "%s/bConfigurationValue", devDir);
    write_file(filePath, "1\n");

    snprintf(filePath, sizeof(filePath), "%s/bNumInterfaces", devDir);
    write_file(filePath, "1\n");

    /* Read USB descriptors from the fd and write to fake sysfs.
     * libusb reads "descriptors" file to get device/config descriptors. */
    snprintf(filePath, sizeof(filePath), "%s/descriptors", devDir);
    {
        int dupFd = dup(fd);
        if (dupFd >= 0) {
            lseek(dupFd, 0, SEEK_SET);
            char descBuf[4096];
            ssize_t n = read(dupFd, descBuf, sizeof(descBuf));
            close(dupFd);
            if (n > 0) {
                FILE *f = fopen(filePath, "wb");
                if (f) {
                    fwrite(descBuf, 1, (size_t)n, f);
                    fclose(f);
                    LOGI("Wrote %zd bytes of USB descriptors to %s", n, filePath);
                }
            } else {
                LOGE("Failed to read USB descriptors from fd=%d (n=%zd errno=%d)", fd, n, errno);
            }
        }
    }

    LOGI("Registered fd: bus=%d dev=%d fd=%d", busNum, devAddr, fd);
    LOGI("fake_usb: %s  fake_sysfs: %s  devDir: %s", g_fake_usb_dir, g_fake_sysfs_dir, devDir);

    typedef void (*set_android_fd_func)(uint8_t, uint8_t, int);
    auto fn = (set_android_fd_func)dlsym(RTLD_DEFAULT, "libusb_set_android_fd");
    if (fn) {
        fn((uint8_t)busNum, (uint8_t)devAddr, fd);
        LOGI("libusb_set_android_fd(bus=%d, dev=%d, fd=%d) OK", busNum, devAddr, fd);
    } else {
        LOGI("libusb_set_android_fd not available (stock libusb?)");
    }

    g_real_open = (open_func_t)dlsym(RTLD_DEFAULT, "open");
    g_real_opendir = (opendir_func_t)dlsym(RTLD_DEFAULT, "opendir");

    env->ReleaseStringUTFChars(jCacheDir, cacheDir);
    return JNI_TRUE;
}

static int g_hooks_installed = 0;

extern "C"
JNIEXPORT jint JNICALL
Java_com_indigo_mobileobservatory_camera_UsbHelper_nativeInstallHooks(
        JNIEnv *, jclass) {

    if (g_hooks_installed) {
        LOGI("Hooks already installed (%d), skipping re-install", g_hooks_installed);
        return g_hooks_installed;
    }

    g_real_open    = (open_func_t)dlsym(RTLD_DEFAULT, "open");
    g_real_opendir = (opendir_func_t)dlsym(RTLD_DEFAULT, "opendir");
    g_real_dlopen  = (dlopen_func_t)dlsym(RTLD_DEFAULT, "dlopen");
    g_real_stat    = (stat_func_t)dlsym(RTLD_DEFAULT, "stat");
    g_real_access  = (access_func_t)dlsym(RTLD_DEFAULT, "access");
    g_real_readdir = (readdir_func_t)dlsym(RTLD_DEFAULT, "readdir");
    g_real_fstat   = (fstat_func_t)dlsym(RTLD_DEFAULT, "fstat");
    g_real_fopen   = (fopen_func_t)dlsym(RTLD_DEFAULT, "fopen");
    g_real_popen   = (popen_func_t)dlsym(RTLD_DEFAULT, "popen");

    struct plt_hook_entry usb_entries[] = {
        { "open",    (void *)hooked_open,    (void **)&g_real_open },
        { "opendir", (void *)hooked_opendir, (void **)&g_real_opendir },
        { "stat",    (void *)hooked_stat,    (void **)&g_real_stat },
        { "access",  (void *)hooked_access,  (void **)&g_real_access },
        { "readdir", (void *)hooked_readdir, (void **)&g_real_readdir },
        { "fstat",   (void *)hooked_fstat,   (void **)&g_real_fstat },
        { "fopen",   (void *)hooked_fopen,   (void **)&g_real_fopen },
        { "popen",   (void *)hooked_popen,   (void **)&g_real_popen },
    };

    struct plt_hook_entry dlopen_entry[] = {
        { "dlopen",  (void *)hooked_dlopen,  (void **)&g_real_dlopen },
    };

    /* Log loaded Mv/usb libraries */
    dl_iterate_phdr([](struct dl_phdr_info *info, size_t, void *) -> int {
        if (info->dlpi_name && info->dlpi_name[0] &&
            (strstr(info->dlpi_name, "Mv") || strstr(info->dlpi_name, "usb") ||
             strstr(info->dlpi_name, "Usb") || strstr(info->dlpi_name, "GigE")))
            log_to_file("I", "Loaded: %s", info->dlpi_name);
        return 0;
    }, nullptr);

    int total = 0;
    const char *tl_libs[] = { "libMvUsb3vTL.so", "libMVGigEVisionSDK.so" };
    for (int i = 0; i < 2; i++) {
        int h = hook_plt(tl_libs[i], usb_entries, 8);
        LOGI("%d hooks in %s", h, tl_libs[i]);
        total += h;
    }

    int h = hook_plt("libMvCameraControl.so", dlopen_entry, 1);
    LOGI("%d dlopen hooks in libMvCameraControl.so", h);
    total += h;

    LOGI("Total hooks: %d", total);
    g_hooks_installed = total;
    return total;
}

static void ensure_originals() {
    if (!g_real_open)    g_real_open    = (open_func_t)dlsym(RTLD_DEFAULT, "open");
    if (!g_real_opendir) g_real_opendir = (opendir_func_t)dlsym(RTLD_DEFAULT, "opendir");
    if (!g_real_dlopen)  g_real_dlopen  = (dlopen_func_t)dlsym(RTLD_DEFAULT, "dlopen");
    if (!g_real_stat)    g_real_stat    = (stat_func_t)dlsym(RTLD_DEFAULT, "stat");
    if (!g_real_access)  g_real_access  = (access_func_t)dlsym(RTLD_DEFAULT, "access");
    if (!g_real_readdir) g_real_readdir = (readdir_func_t)dlsym(RTLD_DEFAULT, "readdir");
    if (!g_real_fstat)   g_real_fstat   = (fstat_func_t)dlsym(RTLD_DEFAULT, "fstat");
    if (!g_real_fopen)   g_real_fopen   = (fopen_func_t)dlsym(RTLD_DEFAULT, "fopen");
    if (!g_real_popen)   g_real_popen   = (popen_func_t)dlsym(RTLD_DEFAULT, "popen");
}

static int g_zwo_hooks_installed = 0;

extern "C"
JNIEXPORT jint JNICALL
Java_com_indigo_mobileobservatory_camera_UsbHelper_nativeInstallZwoHooks(
        JNIEnv *, jclass) {

    if (g_zwo_hooks_installed) {
        LOGI("ZWO hooks already installed (%d), skipping", g_zwo_hooks_installed);
        return g_zwo_hooks_installed;
    }

    ensure_originals();

    struct plt_hook_entry usb_entries[] = {
        { "open",    (void *)hooked_open,    (void **)&g_real_open },
        { "opendir", (void *)hooked_opendir, (void **)&g_real_opendir },
        { "stat",    (void *)hooked_stat,    (void **)&g_real_stat },
        { "access",  (void *)hooked_access,  (void **)&g_real_access },
        { "readdir", (void *)hooked_readdir, (void **)&g_real_readdir },
        { "fstat",   (void *)hooked_fstat,   (void **)&g_real_fstat },
        { "fopen",   (void *)hooked_fopen,   (void **)&g_real_fopen },
        { "popen",   (void *)hooked_popen,   (void **)&g_real_popen },
    };

    dl_iterate_phdr([](struct dl_phdr_info *info, size_t, void *) -> int {
        if (info->dlpi_name && info->dlpi_name[0] &&
            (strstr(info->dlpi_name, "ASICamera") || strstr(info->dlpi_name, "libusb") ||
             strstr(info->dlpi_name, "zwo")))
            log_to_file("I", "ZWO Loaded: %s", info->dlpi_name);
        return 0;
    }, nullptr);

    int total = 0;
    const char *zwo_libs[] = { "libusb-1.0.so", "libASICamera2.so" };
    for (int i = 0; i < 2; i++) {
        int h = hook_plt(zwo_libs[i], usb_entries, 8);
        LOGI("%d hooks in %s (ZWO)", h, zwo_libs[i]);
        total += h;
    }

    LOGI("Total ZWO hooks: %d", total);
    g_zwo_hooks_installed = total;
    return total;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_indigo_mobileobservatory_camera_UsbHelper_nativeClearUsbFds(
        JNIEnv *, jclass) {
    for (int i = 0; i < MAX_FDS; i++)
        g_usb_fds[i].valid = 0;
    g_fake_usb_dir[0] = 0;
    g_fake_sysfs_dir[0] = 0;
    LOGI("Cleared USB fd registry");
}
