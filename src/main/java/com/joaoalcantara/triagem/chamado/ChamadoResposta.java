package com.joaoalcantara.triagem.chamado;

import java.time.Instant;
import java.util.UUID;

public record ChamadoResposta(
        UUID id,
        String texto,
        StatusChamado status,
        Instant criadoEm) {

    public static ChamadoResposta de(Chamado chamado) {
        return new ChamadoResposta(
                chamado.getId(),
                chamado.getTexto(),
                chamado.getStatus(),
                chamado.getCriadoEm());
    }
}
