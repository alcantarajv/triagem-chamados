package com.joaoalcantara.triagem.triagem;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.joaoalcantara.triagem.chamado.Categoria;
import com.joaoalcantara.triagem.chamado.Chamado;
import com.joaoalcantara.triagem.chamado.ChamadoRepositorio;
import com.joaoalcantara.triagem.chamado.ChamadoResposta;
import com.joaoalcantara.triagem.chamado.ChamadoServico;
import com.joaoalcantara.triagem.chamado.Prioridade;
import com.joaoalcantara.triagem.chamado.StatusChamado;
import com.joaoalcantara.triagem.infra.ProvedorFalso;
import com.joaoalcantara.triagem.infra.AmbienteDeTeste;
import com.joaoalcantara.triagem.infra.TesteDeIntegracao;

/** O worker triando chamados com o Gemini simulado pelo WireMock. */
@TesteDeIntegracao
class TriagemIT {

    @Autowired
    private AmbienteDeTeste ambiente;

    private static final String TEXTO = "O sistema caiu e ninguem da equipe consegue emitir nota fiscal desde as 9h.";

    @Autowired
    private TriadorDeChamados triador;

    @Autowired
    private ChamadoServico servico;

    @Autowired
    private ChamadoRepositorio repositorio;

    @Autowired
    private WireMockServer gemini;

    @BeforeEach
    void limpar() {
        ambiente.limpar();
    }

    private Chamado recarregar(UUID id) {
        return repositorio.findById(id).orElseThrow();
    }

    @Test
    @DisplayName("aplica a classificacao do provedor e o chamado fica TRIADO")
    void triaComARespostaDoProvedor() {
        gemini.stubFor(ProvedorFalso.chat()
                .willReturn(ProvedorFalso.classifica("TECNICO", "CRITICA", "Emissao de nota fiscal parada para toda a equipe")));
        UUID id = servico.registrar(TEXTO).getId();

        assertThat(triador.processarProximo()).isTrue();

        Chamado triado = recarregar(id);
        assertThat(triado.getStatus()).isEqualTo(StatusChamado.TRIADO);
        assertThat(triado.getCategoria()).isEqualTo(Categoria.TECNICO);
        assertThat(triado.getPrioridade()).isEqualTo(Prioridade.CRITICA);
        assertThat(triado.getResumo()).isEqualTo("Emissao de nota fiscal parada para toda a equipe");
        assertThat(triado.getProvedor()).isEqualTo("gemini");
        assertThat(triado.getTriadoEm()).isNotNull();
        assertThat(triado.getReservadoAte()).isNull();

        // E a API passa a mostrar a triagem.
        ChamadoResposta resposta = ChamadoResposta.de(triado);
        assertThat(resposta.triagem()).isNotNull();
        assertThat(resposta.triagem().categoria()).isEqualTo(Categoria.TECNICO);
    }

    @Test
    @DisplayName("envia ao provedor a chave, o modelo, o esquema JSON e o texto entre marcas")
    void montaARequisicao() {
        gemini.stubFor(ProvedorFalso.chat()
                .willReturn(ProvedorFalso.classifica("TECNICO", "CRITICA", "Nota fiscal parada")));
        servico.registrar(TEXTO);

        triador.processarProximo();

        gemini.verify(exactly(1), postRequestedFor(urlPathEqualTo(ProvedorFalso.CAMINHO))
                .withHeader("Authorization", equalTo("Bearer " + ProvedorFalso.CHAVE))
                .withRequestBody(matchingJsonPath("$.model", equalTo(ProvedorFalso.MODELO)))
                // Saida estruturada nativa: o esquema vai na requisicao, nao so no prompt.
                .withRequestBody(matchingJsonPath("$.response_format.type", equalTo("json_schema")))
                .withRequestBody(containing("CRITICA"))
                .withRequestBody(containing("<chamado>"))
                .withRequestBody(containing(TEXTO)));
    }

    @Test
    @DisplayName("erro 400 manda o chamado para triagem manual com uma unica requisicao: nao se repete o que foi recusado, nem se pede correcao a quem nao respondeu")
    void erroDefinitivoSemRepeticao() {
        gemini.stubFor(ProvedorFalso.chat().willReturn(aResponse().withStatus(400)));
        UUID id = servico.registrar(TEXTO).getId();

        triador.processarProximo();

        assertThat(recarregar(id).getStatus()).isEqualTo(StatusChamado.PENDENTE_TRIAGEM_MANUAL);
        gemini.verify(exactly(1), postRequestedFor(urlPathEqualTo(ProvedorFalso.CAMINHO)));
    }

    @Test
    @DisplayName("fila vazia: nenhuma requisicao ao provedor")
    void filaVazia() {
        assertThat(triador.processarProximo()).isFalse();

        gemini.verify(exactly(0), postRequestedFor(urlPathEqualTo(ProvedorFalso.CAMINHO)));
    }

    @Test
    @DisplayName("chamado ja triado nao volta para a fila")
    void triadoNaoVoltaParaAFila() {
        gemini.stubFor(ProvedorFalso.chat()
                .willReturn(ProvedorFalso.classifica("DUVIDA", "BAIXA", "Como exportar relatorio")));
        servico.registrar(TEXTO);

        assertThat(triador.processarProximo()).isTrue();
        assertThat(triador.processarProximo()).isFalse();

        gemini.verify(exactly(1), postRequestedFor(urlPathEqualTo(ProvedorFalso.CAMINHO)));
    }
}
