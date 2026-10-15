package com.joaoalcantara.triagem.chamado;

import java.time.Instant;
import java.util.UUID;

/**
 * Chamado como a API o mostra.
 *
 * <p>{@code triagem} fica {@code null} enquanto o chamado nao for triado: o
 * cliente distingue "ainda nao classificado" de "classificado" pelo proprio
 * campo, sem precisar interpretar o status.</p>
 *
 * <p>{@code proximaTentativaAutomatica} so aparece num chamado pendente que
 * ainda vai ser revisitado: diz ao cliente quando olhar de novo. Pendente com
 * esse campo {@code null} esgotou as tentativas automaticas e espera uma
 * pessoa.</p>
 */
public record ChamadoResposta(
        UUID id,
        String texto,
        StatusChamado status,
        Instant criadoEm,
        TriagemResposta triagem,
        Instant proximaTentativaAutomatica) {

    /**
     * @param doCache a classificacao foi reaproveitada de um texto igual, sem
     *                chamar provedor; {@code provedor} e entao quem o classificou
     *                originalmente
     */
    public record TriagemResposta(
            Categoria categoria,
            Prioridade prioridade,
            String resumo,
            String provedor,
            boolean doCache,
            Instant triadoEm) {
    }

    public static ChamadoResposta de(Chamado chamado) {
        TriagemResposta triagem = chamado.getStatus() != StatusChamado.TRIADO ? null
                : new TriagemResposta(
                        chamado.getCategoria(),
                        chamado.getPrioridade(),
                        chamado.getResumo(),
                        chamado.getProvedor(),
                        chamado.isVeioDoCache(),
                        chamado.getTriadoEm());

        return new ChamadoResposta(
                chamado.getId(),
                chamado.getTexto(),
                chamado.getStatus(),
                chamado.getCriadoEm(),
                triagem,
                chamado.getProximaTentativaEm());
    }
}
