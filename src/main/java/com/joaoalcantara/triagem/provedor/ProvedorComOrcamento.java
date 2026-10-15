package com.joaoalcantara.triagem.provedor;

import java.time.ZoneId;
import java.util.List;
import java.util.function.Supplier;

import com.joaoalcantara.triagem.custo.OrcamentoDiario;
import com.joaoalcantara.triagem.infra.MetricasDaTriagem;

/**
 * Confere o orcamento diario do provedor antes de cada requisicao.
 *
 * <p>Fica no ponto mais interno da pilha, colado ao cliente HTTP: conta cada
 * requisicao que de fato sai, inclusive as novas tentativas e as correcoes,
 * porque e assim que o provedor conta a cota. Do lado de fora do circuit
 * breaker, gastaria orcamento com chamadas que o circuito aberto barraria.</p>
 */
public class ProvedorComOrcamento implements ProvedorDeIa {

    private final ProvedorDeIa provedor;
    private final OrcamentoDiario orcamento;
    private final int requisicoesPorDia;
    private final ZoneId fuso;
    private final MetricasDaTriagem metricas;

    public ProvedorComOrcamento(ProvedorDeIa provedor, OrcamentoDiario orcamento, int requisicoesPorDia, ZoneId fuso,
                                MetricasDaTriagem metricas) {
        this.provedor = provedor;
        this.orcamento = orcamento;
        this.requisicoesPorDia = requisicoesPorDia;
        this.fuso = fuso;
        this.metricas = metricas;
    }

    @Override
    public String nome() {
        return provedor.nome();
    }

    @Override
    public String classificar(String texto) {
        return dentroDoOrcamento(() -> provedor.classificar(texto));
    }

    @Override
    public String corrigir(String texto, String respostaAnterior, List<String> erros) {
        return dentroDoOrcamento(() -> provedor.corrigir(texto, respostaAnterior, erros));
    }

    private String dentroDoOrcamento(Supplier<String> chamada) {
        if (!orcamento.reservar(provedor.nome(), requisicoesPorDia, fuso)) {
            metricas.provedorPulado(provedor.nome(), "orcamento_esgotado");
            throw FalhaNoProvedorException.orcamentoEsgotado(provedor.nome(), requisicoesPorDia);
        }
        return chamada.get();
    }
}
