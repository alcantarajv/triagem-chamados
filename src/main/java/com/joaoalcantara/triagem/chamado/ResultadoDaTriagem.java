package com.joaoalcantara.triagem.chamado;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Uma triagem automatica bem-sucedida: a classificacao e como ela foi obtida.
 *
 * @param provedor              quem classificou; quando {@code doCache}, quem
 *                              classificou originalmente o texto guardado
 * @param doCache               reaproveitada de um texto igual, sem chamar provedor
 * @param provedoresQueFalharam os que falharam antes, na ordem da cadeia; vazio
 *                              quando o principal resolveu
 * @param duracao               da reserva ao resultado, sem a espera na fila
 */
public record ResultadoDaTriagem(Classificacao classificacao, String provedor, boolean doCache,
                                 List<String> provedoresQueFalharam, Duration duracao) {

    public ResultadoDaTriagem {
        Objects.requireNonNull(classificacao, "classificacao");
        Objects.requireNonNull(provedor, "provedor");
        provedoresQueFalharam = List.copyOf(provedoresQueFalharam);
        Objects.requireNonNull(duracao, "duracao");
    }
}
