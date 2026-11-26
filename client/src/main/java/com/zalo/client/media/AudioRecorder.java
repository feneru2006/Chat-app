package com.zalo.client.media;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.TargetDataLine;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;

/** Ghi âm 16kHz / 16-bit / Mono (tiêu chí Sound) và phát lại WAV bằng Clip (Thành viên 3). */
public final class AudioRecorder {
    public static final AudioFormat FORMAT = new AudioFormat(16000f, 16, 1, true, false);

    public record Result(File wav, int durationSec) {}

    private TargetDataLine mic;
    private volatile boolean recording;
    private final ByteArrayOutputStream pcm = new ByteArrayOutputStream();
    private Thread thread;

    public void start() throws LineUnavailableException {
        mic = (TargetDataLine) AudioSystem.getLine(new DataLine.Info(TargetDataLine.class, FORMAT));
        mic.open(FORMAT);
        mic.start();
        recording = true;
        pcm.reset();
        thread = new Thread(() -> {
            byte[] b = new byte[4096];
            while (recording) {
                int n = mic.read(b, 0, b.length);
                if (n > 0) pcm.write(b, 0, n);
            }
        }, "audio-record");
        thread.setDaemon(true);
        thread.start();
    }

    /** Dừng & ghi ra WAV. @return null nếu quá ngắn (< 1 giây). */
    public Result stop(File dest) throws IOException {
        recording = false;
        try { if (thread != null) thread.join(500); } catch (InterruptedException ignored) { }
        mic.stop();
        mic.close();
        byte[] data = pcm.toByteArray();
        int sec = data.length / (16000 * 2);
        if (sec < 1) return null;
        dest.getParentFile().mkdirs();
        try (AudioInputStream ais = new AudioInputStream(new ByteArrayInputStream(data), FORMAT, data.length / 2)) {
            AudioSystem.write(ais, AudioFileFormat.Type.WAVE, dest);
        }
        return new Result(dest, sec);
    }

    public void cancel() {
        recording = false;
        if (mic != null) { mic.stop(); mic.close(); }
    }

    private static Clip current;

    /** Phát WAV (dừng đoạn đang phát nếu có). */
    public static synchronized void play(File wav) throws Exception {
        if (current != null) { current.stop(); current.close(); }
        current = AudioSystem.getClip();
        current.open(AudioSystem.getAudioInputStream(wav));
        current.start();
    }
}
