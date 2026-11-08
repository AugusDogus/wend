package dev.augie.roadhome;

import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.os.UserHandle;
import java.util.List;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowLauncherApps;

/** LauncherApps that refuses every app list query, so AppCatalog's load fails. */
@Implements(LauncherApps.class)
public class UnavailableLauncherApps extends ShadowLauncherApps {
    @Implementation
    @Override
    protected List<LauncherActivityInfo> getActivityList(String packageName, UserHandle user) {
        throw new SecurityException("LauncherApps refused the app list in this test");
    }
}
