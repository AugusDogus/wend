package dev.augie.wend;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class MapsTaskTest {
    @Test public void missingTaskAllowsInitialLaunch() {
        assertEquals(-1, MapsTask.selectId(Collections.emptyList(), 42));
    }
    @Test public void existingTaskIsAdoptedAfterLauncherRestart() {
        assertEquals(656, MapsTask.selectId(Collections.singletonList(656), -1));
    }
    @Test public void staleRememberedTaskDoesNotCauseAnotherLaunch() {
        assertEquals(656, MapsTask.selectId(Collections.singletonList(656), 42));
    }
    @Test public void rememberedNavigationTaskWinsOverMoreRecentDuplicate() {
        assertEquals(656, MapsTask.selectId(Arrays.asList(700, 656), 656));
    }
    @Test(expected = IllegalStateException.class) public void unknownDuplicatesMustNotCreateAnotherTask() {
        MapsTask.selectId(Arrays.asList(700, 656), -1);
    }
}
