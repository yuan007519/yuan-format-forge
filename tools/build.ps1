$ErrorActionPreference = "Continue"
$root = "C:\fmtforge-build"
$proj = "$root\project"
$sdk  = "$root\android-sdk"
$bt   = "$sdk\build-tools\34.0.0"
$jar  = "$sdk\platforms\android-34\android.jar"
$jdk  = "$root\jdk\jdk-17.0.21.1+2"
$out  = "$root\out"
$env:JAVA_HOME = $jdk

$ffJar = "$root\ffmpeg-kit\aar\classes.jar"
$ffDep = "$root\ffmpeg-kit\smart-exception.jar;$root\ffmpeg-kit\smart-exception-common.jar"

New-Item -ItemType Directory -Force "$out\gen" | Out-Null
New-Item -ItemType Directory -Force "$out\classes" | Out-Null
New-Item -ItemType Directory -Force "$out\dex" | Out-Null
Get-ChildItem "$out\classes" -Recurse -Filter *.class -ErrorAction SilentlyContinue | Remove-Item -Force
Remove-Item "$out\gen\com\fmtlab\toolbox\R.java" -Force -ErrorAction SilentlyContinue
Get-ChildItem "$out\dex" -Filter *.dex -ErrorAction SilentlyContinue | Remove-Item -Force

& "$bt\aapt2.exe" compile --dir "$proj\res" -o "$out\res.zip" 2>&1 | Out-File -Encoding utf8 "$out\aapt-compile.log"
if ($LASTEXITCODE -ne 0) { "AAPT2 COMPILE FAILED"; exit 1 }

& "$bt\aapt2.exe" link -o "$out\base.apk" -I $jar --manifest "$proj\AndroidManifest.xml" --java "$out\gen" --min-sdk-version 24 --target-sdk-version 34 --version-code 11 --version-name 1.5.3 --auto-add-overlay "$out\res.zip" 2>&1 | Out-File -Encoding utf8 "$out\aapt-link.log"
if ($LASTEXITCODE -ne 0) { "AAPT2 LINK FAILED"; exit 1 }

$cp = "$jar;$ffJar;$ffDep"
$srcs = @(Get-ChildItem "$proj\src" -Recurse -Filter *.java | ForEach-Object { $_.FullName })
$srcs += "$out\gen\com\fmtlab\toolbox\R.java"
& "$jdk\bin\javac.exe" -source 8 -target 8 -encoding UTF-8 -Xlint:-options -classpath $cp -d "$out\classes" $srcs 2>&1 | Out-File -Encoding utf8 "$out\javac.log"
if ($LASTEXITCODE -ne 0) { "JAVAC FAILED"; exit 1 }

# 原生库：只打包真机用得到的两个 ABI
$libout = "$out\lib"
Remove-Item $libout -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $libout | Out-Null
foreach ($abi in @("arm64-v8a", "armeabi-v7a")) {
    $src = "$root\ffmpeg-kit\aar\jni\$abi"
    $dst = "$libout\$abi"
    New-Item -ItemType Directory -Force $dst | Out-Null
    Copy-Item "$src\*.so" $dst -Force
}

& "$jdk\bin\jar.exe" cf "$out\classes.jar" -C "$out\classes" .
& "$bt\d8.bat" --release --lib $jar --min-api 24 --output "$out\dex" "$out\classes.jar" $ffJar "$root\ffmpeg-kit\smart-exception.jar" "$root\ffmpeg-kit\smart-exception-common.jar" 2>&1 | Out-File -Encoding utf8 "$out\d8.log"
if ($LASTEXITCODE -ne 0) { "D8 FAILED"; exit 1 }

python "$out\pkg.py" | Out-String | Out-File -Encoding utf8 "$out\pkg.log"

& "$bt\zipalign.exe" -p -f 4 "$out\withdex.apk" "$out\aligned.apk"
if ($LASTEXITCODE -ne 0) { "ZIPALIGN FAILED"; exit 1 }

Remove-Item "$out\app-release.apk" -Force -ErrorAction SilentlyContinue
& "$bt\apksigner.bat" sign --ks "$out\fmtforge.jks" --ks-pass pass:fmtforge --key-pass pass:fmtforge --ks-key-alias fmtforge --v1-signing-enabled true --v2-signing-enabled true --out "$out\app-release.apk" "$out\aligned.apk" 2>&1 | Out-File -Encoding utf8 "$out\sign.log"
if ($LASTEXITCODE -ne 0) { "SIGN FAILED"; exit 1 }

& "$bt\apksigner.bat" verify --print-certs "$out\app-release.apk" 2>&1 | Out-File -Encoding utf8 "$out\verify.log"
"BUILD OK, verify exit=$LASTEXITCODE, size=$([math]::Round((Get-Item "$out\app-release.apk").Length/1MB,1))MB"
