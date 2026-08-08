package com.mola.cmd.proxy.app.acp.channel.wecom;

import org.junit.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

import static org.junit.Assert.*;

public class WeComMediaDownloaderTest {

    @Test
    public void decryptsOfficialAesCbcAndThirtyTwoBytePkcs7Padding() throws Exception {
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) key[i] = (byte) (i + 1);
        byte[] plain = "wecom attachment".getBytes(StandardCharsets.UTF_8);
        int padding = 32 - plain.length % 32;
        byte[] padded = Arrays.copyOf(plain, plain.length + padding);
        Arrays.fill(padded, plain.length, padded.length, (byte) padding);
        Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                new IvParameterSpec(Arrays.copyOf(key, 16)));

        byte[] decrypted = WeComMediaDownloader.decrypt(cipher.doFinal(padded),
                Base64.getEncoder().encodeToString(key));

        assertArrayEquals(plain, decrypted);
    }

    @Test
    public void parsesUtf8AndQuotedContentDispositionNames() {
        assertEquals("测试.png", WeComMediaDownloader.contentDispositionFileName(
                "attachment; filename*=UTF-8''%E6%B5%8B%E8%AF%95.png"));
        assertEquals("report.pdf", WeComMediaDownloader.contentDispositionFileName(
                "attachment; filename=\"report.pdf\""));
    }
}
