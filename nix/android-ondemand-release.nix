# Release-branch only: unsigned release APK for the signed Obtainium
# releases (.github/workflows/android-ondemand-release.yml).
{
  tincr-ondemand-app,
  versionCode ? 1,
}:
tincr-ondemand-app.overrideAttrs (old: {
  pname = "tincr-ondemand-release";
  postPatch = old.postPatch + ''
    sed -i 's/versionCode = 1$/versionCode = ${toString versionCode}/' app/build.gradle.kts
  '';
  gradleBuildTask = [ "assembleRelease" ];
  # lint-gradle is not in the offline dependency lockfile.
  gradleFlags = (old.gradleFlags or [ ]) ++ [
    "-x"
    "lintVitalAnalyzeRelease"
    "-x"
    "lintVitalRelease"
  ];
  installPhase = ''
    runHook preInstall
    install -Dm644 app/build/outputs/apk/release/app-release-unsigned.apk \
      $out/tincr-ondemand-unsigned.apk
    runHook postInstall
  '';
})
