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
 * moram em tres lugares — o tempo limite do provedor, a politica de repeticao e
 * a reserva —, e mudar um sem os outros e facil. Esta conferencia transforma o
 * erro silencioso de producao num erro barulhento na largada.</p>
 */
@Component
public class ConferenciaDaReserva {

    /** Uma triagem faz no maximo duas chamadas ao provedor: o pedido e a correcao. */
    static final int CHAMADAS_POR_TRIAGEM = 2;

    public ConferenciaDaReserva(PropriedadesDoWorker worker, PropriedadesDosProvedores provedores,
                                PropriedadesDaRepeticao repeticao) {
        Duration pior = piorCaso(provedores.gemini(), repeticao);
        if (worker.reserva().compareTo(pior) <= 0) {
            throw new IllegalStateException(("triagem.worker.reserva (%ds) precisa ser maior que a triagem mais "
                    + "demorada possivel (%ds): %d chamadas x (%d tentativas x tempo limite de %ds + %d esperas de "
                    + "ate %ds). Aumente a reserva ou reduza o tempo limite, as tentativas ou a espera maxima.")
                    .formatted(worker.reserva().toSeconds(), pior.toSeconds(), CHAMADAS_POR_TRIAGEM,
                            repeticao.tentativas(), provedores.gemini().tempoLimite().toSeconds(),
                            repeticao.tentativas() - 1, repeticao.esperaMaxima().toSeconds()));
        }
    }

    static Duration piorCaso(PropriedadesDoProvedor provedor, PropriedadesDaRepeticao repeticao) {
        Duration porChamada = provedor.tempoLimite().multipliedBy(repeticao.tentativas())
                .plus(repeticao.esperaMaxima().multipliedBy(repeticao.tentativas() - 1L));
        return porChamada.multipliedBy(CHAMADAS_POR_TRIAGEM);
    }
}
