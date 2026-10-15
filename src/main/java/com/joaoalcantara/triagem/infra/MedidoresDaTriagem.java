package com.joaoalcantara.triagem.infra;

import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.joaoalcantara.triagem.chamado.ChamadoRepositorio;
import com.joaoalcantara.triagem.chamado.StatusChamado;
import com.joaoalcantara.triagem.custo.OrcamentoDiario;
import com.joaoalcantara.triagem.provedor.PropriedadesDosProvedores;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;

/**
 * Medidores lidos a cada coleta do Prometheus: o tamanho da fila e o consumo do
 * orcamento de cada provedor.
 *
 * <p>Os contadores dizem o que aconteceu; estes dizem como as coisas estao agora.
 * Uma fila de {@code recebidos} que so cresce e o sintoma de que o worker parou
 * ou os provedores nao dao conta — os chamados continuam entrando, a API
 * continua respondendo 202, e nada mais acusa. Um orcamento usado em 90% as 15h
 * diz que o fallback vai virar o caminho principal antes do fim do dia.</p>
 *
 * <p>Cada leitura e uma consulta ao banco. Ficam baratas pelos indices parciais
 * da fila e da revisao, e a coleta acontece a cada poucos segundos, nao a cada
 * requisicao. Se o banco estiver fora, o medidor fica vazio em vez de derrubar
 * a coleta inteira.</p>
 */
@Component
public class MedidoresDaTriagem implements MeterBinder {

    private static final Logger log = LoggerFactory.getLogger(MedidoresDaTriagem.class);

    private final ChamadoRepositorio chamados;
    private final OrcamentoDiario orcamento;
    private final PropriedadesDosProvedores provedores;

    public MedidoresDaTriagem(ChamadoRepositorio chamados, OrcamentoDiario orcamento,
                              PropriedadesDosProvedores provedores) {
        this.chamados = chamados;
        this.orcamento = orcamento;
        this.provedores = provedores;
    }

    @Override
    public void bindTo(MeterRegistry registro) {
        fila(registro, "recebidos", () -> chamados.countByStatus(StatusChamado.RECEBIDO));
        fila(registro, "pendentes_em_revisao", () -> chamados.countByStatusAndProximaTentativaEmIsNotNull(
                StatusChamado.PENDENTE_TRIAGEM_MANUAL));
        fila(registro, "pendentes_para_pessoa", () -> chamados.countByStatusAndProximaTentativaEmIsNull(
                StatusChamado.PENDENTE_TRIAGEM_MANUAL));

        provedores.emOrdem().forEach((nome, propriedades) -> {
            Gauge.builder("triagem.orcamento.usado",
                            seguro(() -> orcamento.usadoHoje(nome, propriedades.fusoDoOrcamento())))
                    .description("Requisicoes ja feitas hoje ao provedor, no fuso em que ele conta a cota")
                    .tag("provedor", nome)
                    .register(registro);
            Gauge.builder("triagem.orcamento.limite", propriedades::orcamentoDiario)
                    .description("Orcamento diario de requisicoes ao provedor")
                    .tag("provedor", nome)
                    .register(registro);
        });
    }

    private void fila(MeterRegistry registro, String situacao, Supplier<Number> contagem) {
        Gauge.builder("triagem.fila", seguro(contagem))
                .description("Chamados esperando triagem, por situacao")
                .tag("situacao", situacao)
                .register(registro);
    }

    private static Supplier<Number> seguro(Supplier<Number> leitura) {
        return () -> {
            try {
                return leitura.get();
            } catch (RuntimeException e) {
                log.debug("Medidor sem leitura: {}", e.getMessage());
                return Double.NaN;
            }
        };
    }
}
