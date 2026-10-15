package com.joaoalcantara.triagem.custo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NormalizadorDeTextoTest {

    @Test
    @DisplayName("maiusculas, acentos e espacos sobrando nao mudam o texto")
    void variacoesSemSentidoSomem() {
        assertThat(NormalizadorDeTexto.normalizar("  Não consigo   ACESSAR\to sistema\n"))
                .isEqualTo(NormalizadorDeTexto.normalizar("nao consigo acessar o sistema"))
                .isEqualTo("nao consigo acessar o sistema");
    }

    @Test
    @DisplayName("cedilha e outros sinais tambem caem")
    void cedilha() {
        assertThat(NormalizadorDeTexto.normalizar("Cobrança duplicada")).isEqualTo("cobranca duplicada");
    }

    @Test
    @DisplayName("pontuacao e palavras diferentes continuam diferentes: acerto errado de cache e pior que uma chamada a mais")
    void diferencasDeVerdadeFicam() {
        assertThat(NormalizadorDeTexto.normalizar("o sistema caiu?"))
                .isNotEqualTo(NormalizadorDeTexto.normalizar("o sistema caiu"));
        assertThat(NormalizadorDeTexto.normalizar("nao consigo pagar o boleto"))
                .isNotEqualTo(NormalizadorDeTexto.normalizar("consigo pagar o boleto"));
    }

    @Test
    @DisplayName("textos iguais depois de normalizados tem a mesma chave de cache; diferentes, chaves diferentes")
    void chaveDoCache() {
        assertThat(CacheDeClassificacao.chave("Não consigo acessar"))
                .isEqualTo(CacheDeClassificacao.chave("nao   consigo ACESSAR"))
                .hasSize(64);
        assertThat(CacheDeClassificacao.chave("nao consigo acessar"))
                .isNotEqualTo(CacheDeClassificacao.chave("nao consigo pagar"));
    }
}
