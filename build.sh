#!/data/data/com.termux/files/usr/bin/bash
# 一键编译 DS Balance —— 纯 Termux，无需 Gradle / Android Studio
# 依赖: pkg install aapt2 d8 apksigner zip openjdk-17
set -e
cd "$(dirname "$0")"

SDK_MIN=26
SDK_TARGET=35
PLATFORM_ZIP="platform-35_r02.zip"
PLATFORM_URL="https://dl.google.com/android/repository/$PLATFORM_ZIP"

if [ ! -f fw/android.jar ]; then
    echo "[1/6] 下载 Android Platform $SDK_TARGET ..."
    mkdir -p fw && cd fw
    [ -f "$PLATFORM_ZIP" ] || curl -# -L -o "$PLATFORM_ZIP" "$PLATFORM_URL"
    unzip -o -q "$PLATFORM_ZIP" "*/android.jar"
    mv "$(find . -name android.jar | head -1)" ./android.jar
    rm -f "$PLATFORM_ZIP"
    cd ..
else
    echo "[1/6] android.jar 已存在"
fi

if [ ! -f build/ds.jks ]; then
    echo "[2/6] 生成签名密钥 ..."
    mkdir -p build
    keytool -genkeypair -keystore build/ds.jks -alias ds \
        -keyalg RSA -keysize 2048 -validity 10950 \
        -storepass dsbalance -keypass dsbalance \
        -dname "CN=DS Balance, OU=dev, O=ds, L=SZ, S=GD, C=CN"
else
    echo "[2/6] 签名密钥已存在"
fi

echo "[3/6] 编译资源 ..."
mkdir -p build/res build/gen build/classes build/dex
find build/res build/gen build/classes build/dex -mindepth 1 -delete 2>/dev/null || true
mkdir -p build/res build/gen build/classes build/dex
aapt2 compile --dir app/res -o build/res/
aapt2 link -o build/base.apk -I fw/android.jar \
    --manifest app/AndroidManifest.xml --java build/gen \
    --min-sdk-version $SDK_MIN --target-sdk-version $SDK_TARGET \
    build/res/*.flat

echo "[4/6] 编译 Java ..."
javac -source 11 -target 11 -classpath fw/android.jar -d build/classes \
    build/gen/com/dsbalance/R.java src/com/dsbalance/*.java

echo "[5/6] 转换 dex ..."
d8 --lib fw/android.jar --min-api $SDK_MIN \
    --output build/dex $(find build/classes -name "*.class")
cd build
cp base.apk unsigned.apk
cp dex/classes.dex .
zip -q -X unsigned.apk classes.dex

echo "[6/6] 签名 ..."
apksigner sign --ks ds.jks \
    --ks-pass pass:dsbalance --key-pass pass:dsbalance --ks-key-alias ds \
    --min-sdk-version $SDK_MIN --out dsbalance.apk unsigned.apk
apksigner verify dsbalance.apk

echo
echo "完成: $(pwd)/dsbalance.apk"
ls -lh dsbalance.apk
