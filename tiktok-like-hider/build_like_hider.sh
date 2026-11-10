#!/bin/bash
# Build pipeline for aMiNo TikTok Like Hider: aapt2 -> ECJ -> d8 -> zipalign -> apksigner
# Xposed stubs are compile-only (excluded from dex).
set -e
SDK=/home/z/android-sdk
BT=$SDK/build-tools/34.0.0
PLAT=$SDK/platforms/android-33/android.jar
ROOT=/home/z/my-project/tiktok-like-hider
ECJ=/home/z/dl/ecj.jar
KS=/home/z/my-project/amino/signing/debug.keystore
VER=$1
OUT=$ROOT/out
[ -z "$VER" ] && VER="v1.3"

echo "== clean =="
rm -rf $OUT
mkdir -p $OUT/classes $OUT/dex

echo "== 1/5 aapt2 resources =="
$BT/aapt2 compile --dir $ROOT/res -o $OUT/res.zip
$BT/aapt2 link -o $OUT/base.apk -I $PLAT \
  --manifest $ROOT/AndroidManifest.xml \
  -A $ROOT/assets \
  $OUT/res.zip

echo "== 2/5 ECJ compile =="
java -jar $ECJ -nowarn -source 8 -target 8 -encoding UTF-8 \
  -bootclasspath $PLAT \
  -classpath $ROOT/stub \
  -d $OUT/classes \
  $ROOT/src/com/amino/tiktoklikehider/*.java \
  $ROOT/stub/de/robv/android/xposed/*.java \
  $ROOT/stub/de/robv/android/xposed/callbacks/*.java

echo "== 3/5 d8 dex (app classes only, stubs excluded) =="
$BT/d8 --release --min-api 21 --lib $PLAT --output $OUT/dex \
  $(ls $OUT/classes/com/amino/tiktoklikehider/*.class)

echo "== 4/5 insert classes.dex =="
python3 - <<'PYEOF'
import zipfile, os
apk = "/home/z/my-project/tiktok-like-hider/out/base.apk"
dex = "/home/z/my-project/tiktok-like-hider/out/dex/classes.dex"
with open(dex, "rb") as f:
    data = f.read()
# rewrite archive keeping existing entries, appending classes.dex
tmp = apk + ".tmp"
with zipfile.ZipFile(apk, "r") as zin, zipfile.ZipFile(tmp, "w") as zout:
    for item in zin.infolist():
        if item.filename == "classes.dex":
            continue
        zout.writestr(item, zin.read(item.filename))
    zout.writestr("classes.dex", data, compress_type=zipfile.ZIP_DEFLATED)
os.replace(tmp, apk)
print("classes.dex inserted:", len(data), "bytes")
PYEOF

echo "== 5/5 zipalign + sign =="
$BT/zipalign -f 4 $OUT/base.apk $OUT/aligned.apk
java -jar $BT/lib/apksigner.jar sign \
  --ks $KS --ks-pass pass:android --ks-key-alias androiddebugkey --key-pass pass:android \
  --out $OUT/amino-tiktok-like-hider-$VER.apk $OUT/aligned.apk
java -jar $BT/lib/apksigner.jar verify --print-certs $OUT/amino-tiktok-like-hider-$VER.apk | head -5
ls -la $OUT/amino-tiktok-like-hider-$VER.apk
md5sum $OUT/amino-tiktok-like-hider-$VER.apk
echo "BUILD_DONE $VER"
