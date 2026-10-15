package com.joaoalcantara.triagem.infra;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 em hexadecimal: 64 caracteres, o tamanho das colunas de chave. */
public final class Sha256 {

    private Sha256() {
    }

    public static String hex(String texto) {
        try {
            byte[] resumo = MessageDigest.getInstance("SHA-256").digest(texto.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(resumo);
        } catch (NoSuchAlgorithmException impossivel) {
            throw new IllegalStateException("SHA-256 e obrigatorio em toda JVM", impossivel);
        }
    }
}
