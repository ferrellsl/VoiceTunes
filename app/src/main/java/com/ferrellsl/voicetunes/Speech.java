package com.ferrellsl.voicetunes;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.speech.RecognitionService;
import android.speech.SpeechRecognizer;

import java.util.ArrayList;
import java.util.List;

/**
 * Which speech recognizer listens. Normally the phone's default; the settings can name another,
 * and when the one used refuses (error 9, e.g. a maker's own recognizer on a tablet without
 * Google, or one that was never given the microphone) Google's is tried next if it's there.
 */
final class Speech {

    private Speech() {}

    /** A recognizer someone can pick: its service and the name to show. */
    static final class Service {
        final ComponentName component;
        final String label;
        Service(ComponentName component, String label) { this.component = component; this.label = label; }
    }

    /** The recognizers installed on the phone. */
    static List<Service> services(Context c) {
        PackageManager pm = c.getPackageManager();
        List<Service> out = new ArrayList<>();
        for (ResolveInfo r : pm.queryIntentServices(new Intent(RecognitionService.SERVICE_INTERFACE), 0)) {
            ComponentName cn = new ComponentName(r.serviceInfo.packageName, r.serviceInfo.name);
            CharSequence app = r.serviceInfo.applicationInfo.loadLabel(pm);
            CharSequence svc = r.loadLabel(pm);
            String label = app.toString().equals(svc.toString()) ? app.toString() : app + " (" + svc + ")";
            out.add(new Service(cn, label));
        }
        return out;
    }

    /** The one picked in the settings, or null for the phone's default. */
    static ComponentName chosen(SharedPreferences prefs) {
        String s = prefs.getString("recognizer", "");
        return s.isEmpty() ? null : ComponentName.unflattenFromString(s);
    }

    static SpeechRecognizer create(Context c, ComponentName service) {
        return service == null ? SpeechRecognizer.createSpeechRecognizer(c) : SpeechRecognizer.createSpeechRecognizer(c, service);
    }

    /** Another recognizer to try after `tried` refused (Google's first), or null. */
    static ComponentName instead(Context c, ComponentName tried) {
        ComponentName other = null;
        for (Service s : services(c)) {
            if (s.component.equals(tried)) continue;
            if (s.component.getPackageName().startsWith("com.google.")) return s.component;
            if (other == null && tried != null) other = s.component;
        }
        return other;
    }
}
