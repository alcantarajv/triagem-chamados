package com.joaoalcantara.triagem.provedor;

import java.util.List;

/**
 * Os provedores configurados, na ordem em que sao consultados: o primeiro e o
 * principal, os seguintes sao o fallback.
 *
 * <p>Cada um ja vem montado com as protecoes, de fora para dentro: nova
 * tentativa, circuit breaker e o cliente HTTP com tempo limite.</p>
 */
public record ProvedoresDeIa(List<ProvedorDeIa> emOrdem) {

    public ProvedoresDeIa {
        emOrdem = List.copyOf(emOrdem);
    }
}
