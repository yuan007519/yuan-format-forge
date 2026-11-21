import zipfile, shutil, os, glob

out = r"C:\fmtforge-build\out"
src = os.path.join(out, "base.apk")
dst = os.path.join(out, "withdex.apk")
shutil.copyfile(src, dst)

dexes = sorted(glob.glob(os.path.join(out, "dex", "classes*.dex")))
libroot = os.path.join(out, "lib")

added = []
with zipfile.ZipFile(dst, "a", zipfile.ZIP_STORED) as z:
    for d in dexes:
        name = os.path.basename(d)
        z.write(d, name)
        added.append(name)
    if os.path.isdir(libroot):
        for abi in sorted(os.listdir(libroot)):
            d = os.path.join(libroot, abi)
            if not os.path.isdir(d):
                continue
            for so in sorted(os.listdir(d)):
                p = os.path.join(d, so)
                arc = "lib/%s/%s" % (abi, so)
                z.write(p, arc)
                added.append(arc)

print("added %d entries (dex=%d, so=%d)" % (len(added), len(dexes), len(added) - len(dexes)))
print("dex files:", [os.path.basename(x) for x in dexes])
