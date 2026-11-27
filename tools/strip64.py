import zipfile, shutil, os
out = r"C:\fmtforge-build\out"
src = os.path.join(out, "withdex.apk")
dst = os.path.join(out, "dex64.apk")
shutil.copyfile(src, dst)
# 重写：丢掉 armeabi-v7a，其它条目原样复制（保持 stored 压缩方式）
tmp = os.path.join(out, "dex64_tmp.apk")
with zipfile.ZipFile(dst, "r") as zin, zipfile.ZipFile(tmp, "w", zipfile.ZIP_STORED) as zout:
    dropped = 0
    for info in zin.infolist():
        if info.filename.startswith("lib/armeabi-v7a/"):
            dropped += 1
            continue
        data = zin.read(info.filename)
        zout.writestr(info, data)
print("dropped", dropped, "v7a entries; size", os.path.getsize(tmp))
os.replace(tmp, dst)
