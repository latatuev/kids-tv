#!/usr/bin/env bash
set -euo pipefail
# Kids TV v0.25: static AccessibilityService configuration plus startup diagnostics.
# This patch extends clean v0.23 into v0.25. Android Accessibility activation is OPT-IN.
python3 - <<'PY'
from pathlib import Path

root=Path("app/src/main")
java=root/"java/com/latatuev/kidstv"
manifest=root/"AndroidManifest.xml"
gradle=Path("app/build.gradle")
assert java.is_dir() and manifest.is_file() and gradle.is_file(), "ANDROID_PROJECT_MISSING"
s=gradle.read_text()
assert "versionCode 23" in s and "versionName '0.23'" in s, "UNEXPECTED_BASELINE"
s=s.replace("versionCode 23","versionCode 25",1).replace("versionName '0.23'","versionName '0.25'",1)
gradle.write_text(s)

# v0.23 has an explicit version compatibility check. Keep it in sync with
# the deployed Kids TV frontend (without forcing an APK rebuild for every later web update).
main=java/"MainActivity.java"
t=main.read_text()
if 'EXPECTED_FRONTEND_BUILD="20261014.1"' in t:
    t=t.replace('EXPECTED_FRONTEND_BUILD="20261014.1"','EXPECTED_FRONTEND_BUILD="20261014.6"',1)
    main.write_text(t)

# Repair one malformed escaped-quote replacement in the archived v0.23
# MainActivity.java; earlier signed builds failed to compile at this line.
t=main.read_text()
lines=t.splitlines(keepends=True)
fixed=0
for i,line in enumerate(lines):
    if 'raw=raw.replace(' in line and 'u007C' in line:
        indent=line[:len(line)-len(line.lstrip())]
        lines[i]=indent+('raw=raw.replace(' + chr(34) + chr(92)*2 + 'u007C' + chr(34) + ',"|").replace(String.valueOf((char)92)+(char)34,String.valueOf((char)34));')+chr(10)
        fixed+=1
assert fixed==1, "EXPECTED_ONE_KNOWN_JAVA_ESCAPE_BUG"
main.write_text(''.join(lines))

# Arm only when Kids TV's native trampoline launches KinoPub.
# No frontend changes needed.
trampoline=java/"TrampolineActivity.java"
t=trampoline.read_text()
launch='JSONObject result=IntentTools.launchWithFallbacks(this,spec,vars);'
assert t.count(launch)==1, "TRAMPOLINE_LAUNCH_SITE_UNEXPECTED"
arm='''if ("com.kinopub".equals(spec.optString("package",""))) {
                getSharedPreferences("kidstv_kinopub_back", MODE_PRIVATE).edit()
                    .putBoolean("armed",true)
                    .putLong("expires",System.currentTimeMillis()+3L*60L*60L*1000L)
                    .apply();
            }
            '''
t=t.replace(launch,arm+launch,1)
trampoline.write_text(t)

(java/"KinoPubBackAccessibility.java").write_text(r'''package com.latatuev.kidstv;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import org.json.JSONObject;

/**
 * Optional user-enabled TV Back integration. Never reads window content,
 * gestures, touch targets, titles, passwords, or other applications' UI data.
 *
 * Observes only active package and Android class name; consumes Back solely
 * when the foreground is KinoPub's detail InfoActivity and Kids TV armed it.
 */
public final class KinoPubBackAccessibility extends AccessibilityService {
    private String activePackage="";
    private String activeClass="";
    private boolean consumeKeyUp=false;
    private String lastReportedClass="";

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        // Android TV can unbind services when runtime service-info updates
        // interact poorly with its accessibility manager. All settings,
        // including filter-key-events, are declared statically in XML.
        android.util.Log.i("KidsTVBackService","connected");
        logLifecycle("connected");
    }

    @Override public boolean onUnbind(Intent intent){
        android.util.Log.i("KidsTVBackService","unbind");
        logLifecycle("unbind");
        return super.onUnbind(intent);
    }

    @Override public void onDestroy(){
        android.util.Log.i("KidsTVBackService","destroy");
        logLifecycle("destroy");
        super.onDestroy();
    }

    private void logLifecycle(String phase){
        try{
            RemoteControlService.diag("kinopub_back_service",
              new JSONObject().put("phase",phase)
                .put("sdk",android.os.Build.VERSION.SDK_INT));
        }catch(Throwable ignored){}
    }

    private SharedPreferences preferences(){
        return getSharedPreferences("kidstv_kinopub_back",MODE_PRIVATE);
    }

    private boolean armed(){
        SharedPreferences p=preferences();
        return p.getBoolean("armed",false)
            && System.currentTimeMillis()<p.getLong("expires",0L);
    }

    private void disarm(){
        preferences().edit().putBoolean("armed",false).remove("expires").apply();
    }

    private static String value(CharSequence text){
        return text==null?"":text.toString();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event){
        if(event==null || event.getEventType()!=AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)return;
        activePackage=value(event.getPackageName());
        activeClass=value(event.getClassName());
        if("com.kinopub".equals(activePackage) && armed()
                && !activeClass.equals(lastReportedClass)){
            lastReportedClass=activeClass;
            try{RemoteControlService.diag("kinopub_back_window",
                new JSONObject().put("activity_class",activeClass));}
            catch(Exception ignored){}
        }
    }

    @Override protected boolean onKeyEvent(KeyEvent e){
        if(e==null || e.getKeyCode()!=KeyEvent.KEYCODE_BACK)return false;
        if(consumeKeyUp){
            if(e.getAction()==KeyEvent.ACTION_UP)consumeKeyUp=false;
            return true;
        }
        if(e.getAction()!=KeyEvent.ACTION_DOWN || e.getRepeatCount()!=0)return false;
        if(!"com.kinopub".equals(activePackage)
            || !activeClass.endsWith(".InfoActivity")
            || !armed())return false;
        disarm();  // One-shot: no effect on any subsequent normal KinoPub session.
        consumeKeyUp=true;
        try{
            RemoteControlService.diag("kinopub_back_intercepted",
                new JSONObject().put("activity_class",activeClass));
        }catch(Exception ignored){}
        try{
            Intent i=new Intent(this,MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                |Intent.FLAG_ACTIVITY_CLEAR_TOP
                |Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(i);
            return true;
        }catch(Exception error){
            consumeKeyUp=false;
            return false;
        }
    }

    @Override public void onInterrupt(){}
}
''')

strings=root/"res/values/kidstv_back_strings.xml"
strings.parent.mkdir(parents=True,exist_ok=True)
strings.write_text('''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="kinopub_back_service_title">Kids TV — возврат из KinoPub</string>
    <string name="kinopub_back_service_desc">Возвращает в Kids TV по кнопке Назад только из карточки KinoPub, открытой через Kids TV. Не читает содержимое экрана.</string>
</resources>
''')
conf=root/"res/xml/kidstv_kinopub_back.xml"
conf.parent.mkdir(parents=True,exist_ok=True)
conf.write_text('''<?xml version="1.0" encoding="utf-8"?>
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeWindowStateChanged"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:notificationTimeout="0"
    android:accessibilityFlags="flagRequestFilterKeyEvents"
    android:canRequestFilterKeyEvents="true"
    android:description="@string/kinopub_back_service_desc" />
''')

m=manifest.read_text()
assert ".KinoPubBackAccessibility" not in m, "PATCH_ALREADY_APPLIED"
assert "</application>" in m, "MANIFEST_APPLICATION_NOT_FOUND"
addon='''
        <service
            android:name=".KinoPubBackAccessibility"
            android:label="@string/kinopub_back_service_title"
            android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"
            android:exported="true">
            <intent-filter>
                <action android:name="android.accessibilityservice.AccessibilityService" />
            </intent-filter>
            <meta-data android:name="android.accessibilityservice"
                android:resource="@xml/kidstv_kinopub_back" />
        </service>
'''
manifest.write_text(m.replace("</application>",addon+"</application>",1))

assert "class KinoPubBackAccessibility" in (java/"KinoPubBackAccessibility.java").read_text()
assert "FLAG_REQUEST_FILTER_KEY_EVENTS" in (java/"KinoPubBackAccessibility.java").read_text()
assert 'android:canRequestFilterKeyEvents="true"' in conf.read_text()
assert 'android:accessibilityFlags="flagRequestFilterKeyEvents"' in conf.read_text()
assert 'setServiceInfo(' not in (java/"KinoPubBackAccessibility.java").read_text()
assert 'KinoPubBackAccessibility' in manifest.read_text()
print("KIDSTV_V25_ACCESSIBILITY_SERVICE_STATIC_CONFIG_OK")
PY
