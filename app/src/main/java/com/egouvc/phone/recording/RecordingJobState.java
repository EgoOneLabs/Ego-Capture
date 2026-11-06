package com.egouvc.phone.recording;

/** Local save/export belongs to a recording job, not the current USB generation.
 * No file I/O or device access. The last result remains visible until the next job.
 */
public final class RecordingJobState {
    public enum Stage { IDLE, STARTING, RECORDING, FINALIZING, EXPORTING, COMPLETE, FAILED }

    public static final class Snapshot {
        public final int owner;
        public final Stage stage;
        public final String message;
        public final String audioMessage;

        private Snapshot(int owner, Stage stage, String message, String audioMessage) {
            this.owner = owner;
            this.stage = stage;
            this.message = message;
            this.audioMessage = audioMessage;
        }

        public boolean isBusy() {
            return stage == Stage.STARTING || stage == Stage.RECORDING ||
                    stage == Stage.FINALIZING || stage == Stage.EXPORTING;
        }

        public boolean canStopCapture() { return stage == Stage.RECORDING; }
    }

    private Snapshot state = new Snapshot(0, Stage.IDLE, null, null);

    public synchronized Snapshot snapshot() { return state; }

    public synchronized boolean begin(int owner) {
        if (state.isBusy()) return false;
        state = new Snapshot(owner, Stage.STARTING, "正在启动采集…", null);
        return true;
    }

    public synchronized boolean ownsBusyJob(int owner) {
        return state.owner == owner && state.isBusy();
    }

    public synchronized boolean update(int owner, Stage stage, String message) {
        if (!ownsBusyJob(owner) || stage == Stage.IDLE || stage.ordinal() < state.stage.ordinal())
            return false;
        state = new Snapshot(owner, stage, message, state.audioMessage);
        return true;
    }

    public synchronized boolean updateAudio(int owner, String message) {
        if (!ownsBusyJob(owner)) return false;
        state = new Snapshot(owner, state.stage, state.message, message);
        return true;
    }

    /** Safe before the queued recorder stop, including an unplug during startup.
     * Never regresses an already running local export back to finalizing.
     */
    public synchronized boolean requestFinish(String message) {
        if (state.stage != Stage.STARTING && state.stage != Stage.RECORDING) return false;
        return update(state.owner, Stage.FINALIZING, message);
    }
}
