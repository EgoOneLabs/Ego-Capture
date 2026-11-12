package com.egouvc.phone.recording;

import com.egouvc.phone.recording.RecordingJobState.Stage;

/** Also runnable without the Android SDK: java ... RecordingJobStateRegression. */
public final class RecordingJobStateRegression {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static RecordingJobState exporting(int owner) {
        RecordingJobState job = new RecordingJobState();
        check(job.begin(owner), "reserve recording");
        check(job.update(owner, Stage.RECORDING, "recording"), "begin capture");
        check(job.update(owner, Stage.FINALIZING, "saving"), "save raw data");
        check(job.update(owner, Stage.EXPORTING, "1471/1758"), "begin export");
        return job;
    }

    public static void main(String[] args) {
        int passed = 0;
        RecordingJobState job = exporting(1);
        check(!job.requestFinish("USB closed"), "USB close must not regress export");
        check(job.snapshot().isBusy(), "USB close must retain busy state");
        check(job.update(1, Stage.EXPORTING, "1501/1758"), "old USB owner still publishes progress");
        check("1501/1758".equals(job.snapshot().message), "visible progress advances after unplug");
        passed++;

        check(!job.begin(2), "reconnected camera must not start another capture during export");
        check(!job.update(2, Stage.COMPLETE, "wrong recorder"), "new USB recorder cannot finish old job");
        passed++;

        check(job.updateAudio(1, "WAV exported"), "audio completion survives unplug");
        check(job.update(1, Stage.COMPLETE, "saved /session/path"), "completion survives unplug");
        check(!job.snapshot().isBusy() && !job.snapshot().canStopCapture(), "completion releases controls");
        check("saved /session/path".equals(job.snapshot().message), "completion retains saved path");
        check("WAV exported".equals(job.snapshot().audioMessage), "retain audio result");
        check(!job.requestFinish("closed again"), "closing camera after success must not erase result");
        passed++;

        check(!job.update(1, Stage.EXPORTING, "late 1741/1758"), "late progress cannot erase completion");
        check(!job.updateAudio(1, "late recording"), "late audio cannot erase completion");
        passed++;

        check(job.begin(2), "next capture allowed after completion");
        check(job.snapshot().stage == Stage.STARTING && job.snapshot().audioMessage == null,
                "new capture clears old result/audio");
        check(!job.update(1, Stage.FAILED, "old failure"), "old camera cannot overwrite next job");
        check(!job.updateAudio(1, "old audio"), "old camera audio cannot overwrite next job");
        passed++;

        check(job.update(2, Stage.RECORDING, "recording again"), "second capture started");
        check(job.snapshot().canStopCapture(), "stop enabled during actual capture");
        check(job.requestFinish("stopping"), "stop reserves finalization before executor callback");
        check(!job.snapshot().canStopCapture(), "stop disabled while saving/exporting");
        check(!job.update(2, Stage.RECORDING, "delayed recording"), "delayed capture status cannot regress saving");
        passed++;

        check(job.update(2, Stage.FAILED, "raw retained /episode/path"), "failure reported after disconnect");
        check(!job.snapshot().isBusy(), "failure releases busy state");
        check("raw retained /episode/path".equals(job.snapshot().message), "retain failure and raw path");
        check(!job.requestFinish("close"), "close cannot erase failure");
        passed++;

        check(job.begin(2), "another job on same camera session allowed");
        check(job.update(2, Stage.FAILED, "closed before start"), "rejected recorder start clears reservation");
        check(!job.snapshot().isBusy(), "rejected start never leaves UI permanently busy");
        passed++;

        check(job.begin(3), "reserve startup race");
        check(job.requestFinish("unplug during startup"), "unplug can finalize during startup");
        check(!job.update(3, Stage.RECORDING, "late startup callback"), "late startup cannot reenable stop");
        check(job.update(3, Stage.EXPORTING, "exporting"), "queued local work can still finish");
        check(job.update(3, Stage.COMPLETE, "saved"), "startup unplug path can complete");
        passed++;

        job = new RecordingJobState();
        check(!job.update(99, Stage.COMPLETE, "unsolicited"), "unstarted recorder cannot publish");
        check(!job.requestFinish("close idle"), "idle close is harmless");
        check(job.snapshot().stage == Stage.IDLE, "idle remains idle");
        passed++;
        System.out.println("PASS: " + passed + " recording/export lifecycle scenarios");
    }
}
