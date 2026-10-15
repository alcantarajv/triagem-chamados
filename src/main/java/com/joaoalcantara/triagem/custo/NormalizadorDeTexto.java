package com.joaoalcantara.triagem.custo;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Reduz um texto a uma forma canonica, para que variacoes que nao mudam o
 * sentido caiam na mesma entrada do cache.
 *
 * <p>Ignora maiusculas, acentos e espacos sobrando: "Nao consigo  ACESSAR" e
 * "não consigo acessar" sao o mesmo chamado. Mantem a pontuacao e as palavras:
 * normalizar alem disso — tirar pontuacao, ordenar palavras, corrigir grafia —
 * comecaria a juntar textos que nao sao iguais, e um acerto de cache errado e
 * pior que uma chamada a mais, porque entrega a classificacao de outro
 * chamado.</p>
 */
final class NormalizadorDeTexto {

    private static final Pattern MARCAS_DIACRITICAS = Pattern.compile("\\p{M}+");
    private static final Pattern ESPACOS = Pattern.compile("\\s+");

    private NormalizadorDeTexto() {
    }

    static String normalizar(String texto) {
        String semAcentos = MARCAS_DIACRITICAS.matcher(Normalizer.normalize(texto, Normalizer.Form.NFKD)).replaceAll("");
        return ESPACOS.matcher(semAcentos.toLowerCase(Locale.ROOT)).replaceAll(" ").strip();
    }
}
