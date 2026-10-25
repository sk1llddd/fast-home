#!/bin/zsh
# Builds Fast Home -> out/fasthome.apk, and its optional Home-button helper -> out/fasthome-redirect.apk.
# Pictures: drop originals into assets/ (any size). They stay untouched there; the build bundles a 1920x1080 copy of each
# (the TV's screen size), so the APK stays small. Each picture becomes a Background choice named from its file name.
# Install the launcher first (the helper uses a permission it defines):
#   adb -s 192.168.1.136:5555 install -r out/fasthome.apk
#   adb -s 192.168.1.136:5555 install -r out/fasthome-redirect.apk   # optional Home-button helper
#   adb -s 192.168.1.136:5555 shell pm grant fast.home.redirect android.permission.READ_LOGS   # optional, ~380 ms faster Home;
#     grant it before turning the helper on in Settings > Accessibility (it takes effect when the helper starts)
cd "${0:A:h}"
# JDK 17: $JAVA_HOME if set, else Homebrew's openjdk@17 on macOS, else whatever java is on PATH.
if [[ -z $JAVA_HOME ]]; then for d in /opt/homebrew/opt/openjdk@17 /usr/local/opt/openjdk@17; do [[ -d $d ]] && export JAVA_HOME=$d && break; done; fi
[[ -n $JAVA_HOME ]] && export PATH=$JAVA_HOME/bin:$PATH
SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/android-sdk}}
BT=$SDK/build-tools/30.0.3; AJ=$SDK/platforms/android-30/android.jar
for need in javac keytool zip; do command -v $need >/dev/null || { echo "missing: $need (see README > Build)"; exit 1; }; done
[[ -f $AJ && -x $BT/aapt2 ]] || { echo "Android SDK not found at $SDK: set ANDROID_HOME, then: sdkmanager \"build-tools;30.0.3\" \"platforms;android-30\""; exit 1; }
# Signing key: made once and kept out of git. Changing it later means uninstalling the app (and its settings) on the TV.
[[ -f debug.keystore ]] || keytool -genkeypair -keystore debug.keystore -storepass android -keypass android -alias debug \
  -keyalg RSA -keysize 2048 -validity 10000 -dname CN=debug >/dev/null 2>&1 || { echo "keytool failed"; exit 1; }
VER=(--version-code $(date +%y%m%d%H) --version-name $(date +%Y.%m.%d))  # version = build date
rm -rf out && mkdir -p out/assets
for f in assets/*(.N); do
  if command -v ffmpeg >/dev/null; then
    ffmpeg -v error -y -i "$f" -vf "scale=1920:1080:force_original_aspect_ratio=increase,crop=1920:1080" -q:v 3 "out/assets/${f:t:r}.jpg"
  else
    echo "ffmpeg not found: bundling $f at full size"; cp "$f" out/assets/   # ponytail: install ffmpeg (brew install ffmpeg) to shrink pictures
  fi
done

# apk <name> <manifest> <res dir or ""> <assets dir or ""> <sources...>  ->  out/<name>.apk
apk() {
  local name=$1 manifest=$2 res=$3 assets=$4; shift 4
  local w=out/$name
  mkdir -p $w/classes
  javac --release 8 -classpath $AJ -d $w/classes "$@" 2>&1 | grep -v '^Note'
  $BT/d8 --lib $AJ --output $w $(find $w/classes -name '*.class') || exit 1
  local link=(-o $w/base.apk --manifest $manifest -I $AJ --min-sdk-version 22 --target-sdk-version 30 $VER)
  if [[ -n $res ]]; then $BT/aapt2 compile --dir $res -o $w/res.zip || exit 1; link+=($w/res.zip); fi
  if [[ -n $assets ]]; then link+=(-A $assets); fi
  $BT/aapt2 link $link || exit 1
  (cd $w && zip -q base.apk classes.dex && $BT/zipalign -f 4 base.apk aligned.apk \
    && $BT/apksigner sign --ks ../../debug.keystore --ks-pass pass:android --out ../$name.apk aligned.apk) || exit 1
}

apk fasthome AndroidManifest.xml "" out/assets src/fast/{Home,InputActivity,Ui}.java
apk fasthome-redirect redirect/AndroidManifest.xml redirect/res "" src/fast/{HomeRedirect,Ui}.java
ls -la out/*.apk
echo "bundled pictures:"; ls -la out/assets | tail -n +4
