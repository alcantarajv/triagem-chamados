package com.joaoalcantara.triagem.custo;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.joaoalcantara.triagem.chamado.Categoria;
import com.joaoalcantara.triagem.chamado.Chamado;
import com.joaoalcantara.triagem.chamado.ChamadoRepositorio;
import com.joaoalcantara.triagem.chamado.ChamadoServico;
import com.joaoalcantara.triagem.chamado.StatusChamado;
import com.joaoalcantara.triagem.infra.AmbienteDeTeste;
import com.joaoalcantara.triagem.infra.ProvedorFalso;
import com.joaoalcantara.triagem.infra.RelogioAjustavel.ClockMovel;
import com.joaoalcantara.triagem.infra.TesteDeIntegracao;
import com.joaoalcantara.triagem.triagem.TriadorDeChamados;

/** O mesmo texto nao gera outra chamada ao provedor. */
@TesteDeIntegracao
class CacheIT {

    private static final String TEXTO = "Não consigo acessar o sistema desde a troca de senha.";

    @Autowired
    private AmbienteDeTeste ambiente;

    @Autowired
    private TriadorDeChamados triador;

    @Autowired
    private ChamadoServico servico;

    @Autowired
    private ChamadoRepositorio repositorio;

    @Autowired
    private WireMockServer gemini;

    @Autowired
    private WireMockServer groq;

    @Autowired
    private JdbcClient banco;

    @Autowired
    private PropriedadesDoCusto custo;

    @Autowired
    private ClockMovel relogio;

    @BeforeEach
    void limpar() {
        ambiente.limpar();
        gemini.stubFor(ProvedorFalso.chat().willReturn(
                ProvedorFalso.classifica("ACESSO", "ALTA", "Sem acesso ao sistema apos troca de senha")));
    }

    private Chamado triar(String texto) {
        UUID id = servico.registrar(texto).getId();
        triador.processarProximo();
        return repositorio.findById(id).orElseThrow();
    }

    private void verificarRequisicoesAoGemini(int quantidade) {
        gemini.verify(exactly(quantidade), postRequestedFor(urlPathEqualTo(ProvedorFalso.CAMINHO)));
    }

    @Test
    @DisplayName("o mesmo texto duas vezes: a segunda triagem vem do cache, com zero requisicoes ao provedor")
    void mesmoTextoVemDoCache() {
        Chamado primeiro = triar(TEXTO);
        Chamado segundo = triar(TEXTO);

        verificarRequisicoesAoGemini(1);
        groq.verify(exactly(0), postRequestedFor(urlPathEqualTo(ProvedorFalso.CAMINHO)));

        assertThat(primeiro.isVeioDoCache()).isFalse();
        assertThat(segundo.getStatus()).isEqualTo(StatusChamado.TRIADO);
        assertThat(segundo.isVeioDoCache()).isTrue();
        assertThat(segundo.getCategoria()).isEqualTo(Categoria.ACESSO);
        assertThat(segundo.getResumo()).isEqualTo(primeiro.getResumo());
        // Quem classificou originalmente continua registrado.
        assertThat(segundo.getProvedor()).isEqualTo("gemini");
    }

    @Test
    @DisplayName("maiusculas, acentos e espacos diferentes tambem acertam o cache")
    void variacoesAcertamOCache() {
        triar(TEXTO);
        Chamado variacao = triar("  NAO consigo acessar o sistema   desde a troca de senha. ");

        verificarRequisicoesAoGemini(1);
        assertThat(variacao.isVeioDoCache()).isTrue();
    }

    @Test
    @DisplayName("texto diferente vai ao provedor")
    void textoDiferente() {
        triar(TEXTO);
        Chamado outro = triar("Nao consigo pagar o boleto deste mes.");

        verificarRequisicoesAoGemini(2);
        assertThat(outro.isVeioDoCache()).isFalse();
    }

    @Test
    @DisplayName("classificacao vencida nao e reaproveitada")
    void cacheVencido() {
        triar(TEXTO);
        relogio.avancar(custo.validadeDoCache().plusSeconds(1));

        Chamado depois = triar(TEXTO);

        verificarRequisicoesAoGemini(2);
        assertThat(depois.isVeioDoCache()).isFalse();
    }

    @Test
    @DisplayName("falha nao vai para o cache: o proximo chamado igual tem uma tentativa de verdade")
    void falhaNaoECacheada() {
        gemini.resetAll();
        gemini.stubFor(ProvedorFalso.chat().willReturn(aResponse().withStatus(400)));
        assertThat(triar(TEXTO).getStatus()).isEqualTo(StatusChamado.PENDENTE_TRIAGEM_MANUAL);

        gemini.resetAll();
        gemini.stubFor(ProvedorFalso.chat().willReturn(
                ProvedorFalso.classifica("ACESSO", "ALTA", "Sem acesso ao sistema")));
        Chamado depois = triar(TEXTO);

        assertThat(depois.getStatus()).isEqualTo(StatusChamado.TRIADO);
        assertThat(depois.isVeioDoCache()).isFalse();
        verificarRequisicoesAoGemini(1);
    }

    @Test
    @DisplayName("o cache guarda o hash do texto, nunca o texto")
    void cacheNaoGuardaOTexto() {
        triar(TEXTO);

        List<String> linhas = banco.sql("SELECT row_to_json(c)::text FROM classificacoes_em_cache c")
                .query(String.class).list();

        // O resumo deriva do texto e pode repetir palavras dele; o que nao pode
        // estar la e o texto do chamado, nem na forma original nem normalizada.
        assertThat(linhas).hasSize(1);
        assertThat(linhas.getFirst()).doesNotContainIgnoringCase("consigo acessar");
        assertThat(linhas.getFirst()).doesNotContainIgnoringCase("desde a troca");
    }

    @Test
    @DisplayName("chamados com o mesmo texto ainda sao chamados distintos, cada um com o seu id")
    void chamadosContinuamDistintos() {
        Chamado primeiro = triar(TEXTO);
        relogio.avancar(Duration.ofSeconds(1));
        Chamado segundo = triar(TEXTO);

        assertThat(segundo.getId()).isNotEqualTo(primeiro.getId());
        assertThat(repositorio.count()).isEqualTo(2);
    }
}
