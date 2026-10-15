package com.joaoalcantara.triagem.custo;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class IdentificadorDeClienteTest {

    private static IdentificadorDeCliente comProxies(int proxies) {
        return new IdentificadorDeCliente(new PropriedadesDoCusto(Duration.ofDays(30), 10, proxies, Duration.ofHours(1)));
    }

    private static MockHttpServletRequest requisicao(String enderecoDaConexao, String xForwardedFor) {
        MockHttpServletRequest requisicao = new MockHttpServletRequest();
        requisicao.setRemoteAddr(enderecoDaConexao);
        if (xForwardedFor != null) {
            requisicao.addHeader("X-Forwarded-For", xForwardedFor);
        }
        return requisicao;
    }

    @Test
    @DisplayName("sem proxy confiavel, vale o endereco da conexao e o cabecalho e ignorado")
    void semProxy() {
        assertThat(comProxies(0).enderecoDe(requisicao("203.0.113.7", "198.51.100.1")))
                .isEqualTo("203.0.113.7");
    }

    @Test
    @DisplayName("com um proxy, vale o ultimo endereco do cabecalho, o que o proxy escreveu")
    void umProxy() {
        assertThat(comProxies(1).enderecoDe(requisicao("10.0.0.5", "203.0.113.7")))
                .isEqualTo("203.0.113.7");
    }

    @Test
    @DisplayName("cliente que forja o cabecalho nao escapa do limite: o endereco forjado fica a esquerda e e ignorado")
    void cabecalhoForjado() {
        // O cliente mandou "X-Forwarded-For: 1.2.3.4"; o proxy acrescentou o
        // endereco real no fim.
        assertThat(comProxies(1).enderecoDe(requisicao("10.0.0.5", "1.2.3.4, 203.0.113.7")))
                .isEqualTo("203.0.113.7");
    }

    @Test
    @DisplayName("com dois proxies, vale o penultimo endereco")
    void doisProxies() {
        assertThat(comProxies(2).enderecoDe(requisicao("10.0.0.5", "1.2.3.4, 203.0.113.7, 10.0.0.9")))
                .isEqualTo("203.0.113.7");
    }

    @Test
    @DisplayName("cabecalho ausente ou mais curto que a cadeia de proxies: vale o endereco da conexao")
    void cabecalhoIncompleto() {
        assertThat(comProxies(1).enderecoDe(requisicao("10.0.0.5", null))).isEqualTo("10.0.0.5");
        assertThat(comProxies(2).enderecoDe(requisicao("10.0.0.5", "203.0.113.7"))).isEqualTo("10.0.0.5");
    }
}
