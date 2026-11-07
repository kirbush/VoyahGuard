package local.codex.voyahcameralab;

import java.io.File;
import java.util.List;

public final class VideoTimelineTest {
  static final long CHUNK = 15000;
  static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
  static VideoTimeline.Chunk chunk(int index, boolean watched) {
    return new VideoTimeline.Chunk(new File("c" + index), index * CHUNK, (index + 1) * CHUNK, 1000 + index, 10, watched);
  }

  public static void main(String[] args) {
    VideoTimeline all = new VideoTimeline(true, false);
    for (int i = 0; i < 8; i++) check(all.chunk(chunk(i, true)).videos.isEmpty(), "Footage without an event waits for the end of the stretch");
    VideoTimeline.Step last = all.finish();
    check(last.videos.size() == 1 && last.videos.get(0).kind == VideoTimeline.Kind.RECORD && last.videos.get(0).chunks.size() == 8, "A whole recording becomes one video");
    check(all.finish().videos.isEmpty(), "A second end publishes nothing");

    VideoTimeline event = new VideoTimeline(true, false);
    for (int i = 0; i < 8; i++) event.chunk(chunk(i, true));
    VideoTimeline.Step started = event.motion(8 * CHUNK, true);
    check(started.videos.size() == 1 && started.videos.get(0).kind == VideoTimeline.Kind.RECORD, "Footage before the pre-roll ends as its own video");
    check(started.videos.get(0).chunks.size() == 6 && event.held().size() == 2, "The last 30 seconds open the event");
    int index = 8;
    for (; index < 9; index++) check(event.chunk(chunk(index, true)).videos.isEmpty(), "An event stays open during the post-roll");
    event.motion((index + 1) * CHUNK - 1000, true);
    for (; index < 11; index++) check(event.chunk(chunk(index, true)).videos.isEmpty(), "New motion extends the same event");
    VideoTimeline.Step ended = event.chunk(chunk(index, true));
    check(ended.videos.size() == 1 && ended.videos.get(0).kind == VideoTimeline.Kind.EVENT, "Silence after the post-roll closes the event");
    check(ended.videos.get(0).chunks.size() == 6 && !event.inEvent(), "The event is one video from pre-roll to post-roll");

    VideoTimeline only = new VideoTimeline(false, false);
    int dropped = 0;
    for (int i = 0; i < 10; i++) {
      VideoTimeline.Step step = only.chunk(chunk(i, true));
      check(step.videos.isEmpty(), "Watched silence never becomes a video");
      dropped += step.dropped.size();
    }
    check(dropped == 8 && only.held().size() == 2, "Only the pre-roll survives");
    check(only.finish().videos.isEmpty(), "A silent recording leaves nothing");

    VideoTimeline eventOnly = new VideoTimeline(false, false);
    for (int i = 0; i < 6; i++) eventOnly.chunk(chunk(i, true));
    check(eventOnly.motion(6 * CHUNK, true).videos.isEmpty(), "Silence before the pre-roll is not kept");
    check(eventOnly.held().size() == 2, "The pre-roll joins the event");
    VideoTimeline.Step stopped = eventOnly.finish();
    check(stopped.videos.size() == 1 && stopped.videos.get(0).kind == VideoTimeline.Kind.EVENT, "Stop closes a running event");

    VideoTimeline blind = new VideoTimeline(false, false);
    for (int i = 0; i < 4; i++) check(blind.chunk(chunk(i, false)).videos.isEmpty(), "Footage nobody watched is kept and stays whole");
    VideoTimeline.Step seen = blind.chunk(chunk(4, true));
    check(seen.videos.size() == 1 && seen.videos.get(0).kind == VideoTimeline.Kind.RECORD && seen.videos.get(0).chunks.size() == 4, "It leaves as one video when the detector returns");
    for (int i = 5; i < 8; i++) blind.chunk(chunk(i, true));
    check(blind.held().size() == 2, "Watched silence after it goes as usual");

    VideoTimeline dead = new VideoTimeline(false, false);
    for (int i = 0; i < 6; i++) dead.chunk(chunk(i, false));
    VideoTimeline.Step end = dead.finish();
    check(end.videos.size() == 1 && end.videos.get(0).chunks.size() == 6, "Without a detector the option loses nothing");

    VideoTimeline full = new VideoTimeline(true, false);
    for (int i = 0; i < 4; i++) full.chunk(chunk(i, true));
    List<VideoTimeline.Chunk> held = full.held();
    check(!full.trim(held.get(1)), "Only the start of the video can be cut");
    check(full.trim(held.get(0)) && full.held().size() == 3, "The oldest chunk goes");
    check(full.trim(held.get(1)) && full.trim(held.get(2)) && !full.trim(held.get(3)), "The last chunk stays");
    VideoTimeline.Step cut = full.finish();
    check(cut.videos.size() == 1 && cut.videos.get(0).trimmed && cut.videos.get(0).chunks.size() == 1, "The video knows its start was cut");
    VideoTimeline far = new VideoTimeline(false, false);
    far.chunk(chunk(0, true)); far.motion(10000, false);
    check(far.inEvent() && !far.inNearEvent(), "Motion further away is an event, not a near one");
    List<VideoTimeline.Video> farVideos = new java.util.ArrayList<>();
    for (int i = 1; i < 5; i++) farVideos.addAll(far.chunk(chunk(i, true)).videos);
    check(farVideos.size() == 1 && farVideos.get(0).kind == VideoTimeline.Kind.CAMERA, "Motion only further away makes a camera video");
    VideoTimeline mixed = new VideoTimeline(false, false);
    mixed.chunk(chunk(0, true)); mixed.motion(5000, false); mixed.motion(12000, true);
    check(mixed.inNearEvent(), "The running event knows somebody came near");
    check(mixed.finish().videos.get(0).kind == VideoTimeline.Kind.EVENT, "Anybody near the car makes it an event");
    VideoTimeline nearOnly = new VideoTimeline(false, true);
    check(nearOnly.motion(5000, false).videos.isEmpty() && !nearOnly.inEvent(), "With near-only, motion further away opens nothing");
    VideoTimeline.Step gone = nearOnly.chunk(chunk(0, true)); for (int i = 1; i < 4; i++) gone = nearOnly.chunk(chunk(i, true));
    check(gone.videos.isEmpty() && !gone.dropped.isEmpty(), "and that footage goes like quiet footage");
    System.out.println("PASS: whole videos, events, pre-roll and post-roll, unwatched footage, trimmed start, near and far motion");
  }
}
