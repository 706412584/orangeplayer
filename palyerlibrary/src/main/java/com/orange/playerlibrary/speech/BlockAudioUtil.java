package com.orange.playerlibrary.speech;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/** 渐进 ASR 音频块处理公共步骤（重采样 + wav 头包装） */
final class BlockAudioUtil {

    private BlockAudioUtil() {
    }

    /** 裸 PCM（源采样率/单声道）→ 16k 单声道 wav */
    static void pcmToWav(File pcm, int srcRate, File outWav) throws Exception {
        File resampled = new File(pcm.getParentFile(), pcm.getName() + "_16k.pcm");
        try {
            AudioResampler.resampleTo16kMono(pcm, srcRate, 1, resampled);
            Files.copy(resampled.toPath(), outWav.toPath(), StandardCopyOption.REPLACE_EXISTING);
            AsrSubtitleGenerator.wrapRiffWavHeader(outWav);
        } finally {
            if (resampled.exists()) {
                resampled.delete();
            }
        }
    }

    static void deleteQuiet(File f) {
        if (f != null && f.exists()) {
            f.delete();
        }
    }
}
