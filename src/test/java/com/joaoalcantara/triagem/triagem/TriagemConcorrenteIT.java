package com.joaoalcantara.triagem.triagem;

import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.joaoalcantara.triagem.chamado.ChamadoRepositorio;
import com.joaoalcantara.triagem.chamado.ChamadoServico;
import com.joaoalcantara.triagem.chamado.StatusChamado;
import com.joaoalcantara.triagem.infra.ProvedorFalso;
import com.joaoalcantara.triagem.infra.TesteDeIntegracao;

/**
 * Varios workers ao mesmo tempo sobre a mesma fila.
 *
 * <p>Cada thread faz o papel de uma instancia da aplicacao: o que as separa e o
 * banco, nao a memoria — o triador nao guarda estado entre chamadas, e cada
 * reserva e uma transacao propria com conexao propria. E o mesmo que aconteceria
 * com duas instancias no Render.</p>
 */
@TesteDeIntegracao
class TriagemConcorrenteIT {

    private static final int CHAMADOS = 30;
    private static final int WORKERS = 4;

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
        repositorio.deleteAll();
        gemini.resetAll();
    }

    @Test
    @DisplayName("quatro workers ao mesmo tempo: cada chamado e triado uma unica vez, e todos trabalham")
    void cadaChamadoUmaUnicaVez() throws Exception {
        // Um pouco de latencia no provedor, para que os workers de fato se
        // sobreponham enquanto esperam a resposta.
        gemini.stubFor(ProvedorFalso.chat()
                .willReturn(ProvedorFalso.classifica("DUVIDA", "BAIXA", "Duvida de uso").withFixedDelay(40)));
        IntStream.rangeClosed(1, CHAMADOS).forEach(n -> servico.registrar(textoDoChamado(n)));

        List<Integer> processadosPorWorker = rodarWorkersAteEsvaziarAFila();

        assertThat(processadosPorWorker.stream().mapToInt(Integer::intValue).sum()).isEqualTo(CHAMADOS);
        // Com SKIP LOCKED nenhum worker fica parado esperando o lock de outro:
        // todos pegam parte da fila.
        assertThat(processadosPorWorker).allMatch(processados -> processados > 0);
        assertThat(repositorio.findAll()).allMatch(c -> c.getStatus() == StatusChamado.TRIADO);

        // O provedor recebeu exatamente uma requisicao por chamado...
        gemini.verify(exactly(CHAMADOS), postRequestedFor(urlPathEqualTo(ProvedorFalso.CAMINHO)));
        // ...e cada texto apareceu em uma, e so uma, delas.
        List<LoggedRequest> requisicoes = gemini.findAll(postRequestedFor(urlPathEqualTo(ProvedorFalso.CAMINHO)));
        for (int n = 1; n <= CHAMADOS; n++) {
            String texto = textoDoChamado(n);
            assertThat(requisicoes).filteredOn(r -> r.getBodyAsString().contains(texto)).hasSize(1);
        }
    }

    /** Solta os workers juntos e devolve quantos chamados cada um processou. */
    private List<Integer> rodarWorkersAteEsvaziarAFila() throws Exception {
        CountDownLatch largada = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(WORKERS);
        try {
            List<Future<Integer>> futuros = new ArrayList<>();
            for (int w = 0; w < WORKERS; w++) {
                futuros.add(workers.submit(() -> {
                    largada.await();
                    int processados = 0;
                    while (triador.processarProximo()) {
                        processados++;
                    }
                    return processados;
                }));
            }
            largada.countDown();

            List<Integer> contagens = new ArrayList<>();
            for (Future<Integer> futuro : futuros) {
                contagens.add(futuro.get());
            }
            return contagens;
        } finally {
            workers.shutdownNow();
        }
    }

    /** Texto unico por chamado, com delimitadores para "[01]" nao casar com "[10]". */
    private static String textoDoChamado(int n) {
        return "Chamado de teste [%02d]: nao consigo gerar o relatorio mensal.".formatted(n);
    }
}
