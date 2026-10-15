package com.joaoalcantara.triagem.triagem;

import java.time.Duration;

import org.springframework.stereotype.Component;

import com.joaoalcantara.triagem.provedor.PropriedadesDaRepeticao;
import com.joaoalcantara.triagem.provedor.PropriedadesDoProvedor;
import com.joaoalcantara.triagem.provedor.PropriedadesDosProvedores;

/**
 * Recusa subir se a reserva do worker for menor que a triagem mais demorada
 * possivel.
 *
 * <p>Se a reserva vencer com a triagem ainda em andamento, outro worker pega o
 * mesmo chamado e o provedor e pago duas vezes. Os numeros que decidem isso
 * moram em varios lugares — o tempo limite de cada provedor, a politica de
 * repeticao, o tamanho da cadeia e a reserva —, e mudar um sem os outros e
 * facil. Esta conferencia transforma o erro silencioso de producao num erro
 * barulhento na largada.</p>
 */
@Component
public class ConferenciaDaReserva {

    /** Uma triagem faz no maximo duas chamadas a cada provedor: o pedido e a correcao. */
    static final int CHAMADAS_POR_PROVEDOR = 2;

    public ConferenciaDaReserva(PropriedadesDoWorker worker, PropriedadesDosProvedores provedores,
                                PropriedadesDaRepeticao repeticao) {
        Duration pior = piorCaso(provedores, repeticao);
        if (worker.reserva().compareTo(pior) <= 0) {
            throw new IllegalStateException(("triagem.worker.reserva (%ds) precisa ser maior que a triagem mais "
                    + "demorada possivel (%ds): para cada provedor da cadeia, %d chamadas x (%d tentativas x tempo "
                    + "limite + %d esperas de ate %ds). Aumente a reserva ou reduza os tempos limite, as tentativas "
                    + "ou a espera maxima.")
                    .formatted(worker.reserva().toSeconds(), pior.toSeconds(), CHAMADAS_POR_PROVEDOR,
                            repeticao.tentativas(), repeticao.tentativas() - 1, repeticao.esperaMaxima().toSeconds()));
        }
    }

    /**
     * Soma o pior caso de todos os provedores configurados, inclusive os que
     * estao sem chave: a conta tem de fechar para a configuracao, nao para o
     * ambiente em que ela calhou de subir.
     */
    static Duration piorCaso(PropriedadesDosProvedores provedores, PropriedadesDaRepeticao repeticao) {
        return provedores.emOrdem().values().stream()
                .map(provedor -> piorCaso(provedor, repeticao))
                .reduce(Duration.ZERO, Duration::plus);
    }

    static Duration piorCaso(PropriedadesDoProvedor provedor, PropriedadesDaRepeticao repeticao) {
        Duration porChamada = provedor.tempoLimite().multipliedBy(repeticao.tentativas())
                .plus(repeticao.esperaMaxima().multipliedBy(repeticao.tentativas() - 1L));
        return porChamada.multipliedBy(CHAMADAS_POR_PROVEDOR);
    }
}
