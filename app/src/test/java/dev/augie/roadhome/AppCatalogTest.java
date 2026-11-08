package dev.augie.roadhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Process;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class AppCatalogTest {
    private final Context context = RuntimeEnvironment.getApplication();

    @Test public void anIconThatCannotBeDrawnKeepsItsAppAndTheRestOfTheList() {
        TestApps.install(context, "com.broken.app", "Broken", new BrokenIcon());
        TestApps.install(context, "com.google.android.apps.maps", "Maps");

        AppCatalog catalog = TestApps.loadedCatalog(context);

        assertEquals(AppCatalog.Status.READY, catalog.status());
        assertEquals(Arrays.asList("Broken", "Maps"), labels(catalog.apps()));
    }

    @Test public void anAppWhoseLabelCannotBeReadIsLeftOutAlone() {
        TestApps.installWithUnreadableLabel(context, "com.broken.app");
        TestApps.install(context, "com.google.android.apps.maps", "Maps");

        AppCatalog catalog = TestApps.loadedCatalog(context);

        assertEquals(AppCatalog.Status.READY, catalog.status());
        assertEquals(Arrays.asList("Maps"), labels(catalog.apps()));
    }

    @Test public void pinsNameAppsInTheLaunchersOwnProfileOnly() {
        TestApps.install(context, "com.mail", "Mail");
        TestApps.install(context, TestApps.workProfile(context), "com.mail", "Mail", new ColorDrawable(Color.BLUE));
        TestApps.install(context, TestApps.workProfile(context), "com.slack", "Slack", new ColorDrawable(Color.RED));

        AppCatalog catalog = TestApps.loadedCatalog(context);

        assertEquals("The drawer lists every profile", 3, catalog.apps().size());
        LauncherApp mail = catalog.pinnedApp("com.mail");
        assertNotNull(mail);
        assertEquals(Process.myUserHandle(), mail.key.user);
        assertNull("A work-profile app cannot be pinned", catalog.pinnedApp("com.slack"));
        assertNull(catalog.pinnedApp("com.not.installed"));
    }

    private static List<String> labels(List<LauncherApp> apps) {
        List<String> labels = new ArrayList<>();
        for (LauncherApp app : apps) labels.add(app.label);
        return labels;
    }

    /** Artwork whose drawing fails, as a corrupt or unsupported app drawable can. */
    private static final class BrokenIcon extends ColorDrawable {
        @Override public void draw(Canvas canvas) { throw new IllegalStateException("Unsupported artwork"); }
    }
}
