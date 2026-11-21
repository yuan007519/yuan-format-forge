import zipfile, os, sys

apk = r'C:\fmtforge-build\out\app-release.apk'
out = []
z = zipfile.ZipFile(apk)
names = z.namelist()
out.append('entries: %d' % len(names))

so = [n for n in names if n.startswith('lib/')]
out.append('so entries: %d' % len(so))
for abi in ('arm64-v8a', 'armeabi-v7a'):
    group = [n for n in so if n.startswith('lib/%s/' % abi)]
    total = sum(z.getinfo(n).file_size for n in group)
    out.append('  %s: %d files, %.1f MB' % (abi, len(group), total / 1024 / 1024))
    for n in group:
        i = z.getinfo(n)
        out.append('    %-40s %8.2f MB  method=%d  offset=%d' %
                   (os.path.basename(n), i.file_size / 1024 / 1024, i.compress_type, i.header_offset))

dex = z.read('classes.dex')
out.append('classes.dex size: %.2f MB' % (len(dex) / 1024 / 1024))
for key in (b'Lcom/arthenica/ffmpegkit/FFmpegKit;', b'Lcom/arthenica/ffmpegkit/NativeLoader;',
            b'Lcom/fmtlab/toolbox/ConvertActivity;', b'Lcom/arthenica/smartexception/'):
    out.append('%s in dex: %s' % (key.decode(), key in dex))

manifest = z.read('AndroidManifest.xml')
out.append('extractNativeLibs attr present: %s' % (b'extractNativeLibs' in z.read('resources.arsc') or b'extractNativeLibs' in manifest))

open(r'C:\fmtforge-build\out\apkcheck.txt', 'w', encoding='utf-8').write('\n'.join(out))
print('\n'.join(out))
