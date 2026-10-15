package com.joaoalcantara.triagem.chamado;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

public interface ChamadoRepositorio extends JpaRepository<Chamado, UUID> {

    /**
     * O chamado RECEBIDO mais antigo que ninguem esta processando, ja travado
     * para esta transacao.
     *
     * <p>{@code SKIP LOCKED} e o que permite varios workers ao mesmo tempo: uma
     * linha travada por outra transacao e simplesmente pulada, em vez de fazer
     * esta esperar. Sem ele, dois workers buscariam o mesmo chamado, o segundo
     * ficaria bloqueado ate o primeiro terminar, e o paralelismo viraria fila
     * indiana.</p>
     *
     * <p>Consulta nativa porque o JPQL nao tem {@code SKIP LOCKED}, e porque o
     * criterio precisa casar com o indice parcial {@code chamados_fila_de_triagem}.</p>
     */
    @Query(value = """
            SELECT * FROM chamados
            WHERE status = 'RECEBIDO'
              AND (reservado_ate IS NULL OR reservado_ate < :agora)
            ORDER BY criado_em
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<Chamado> buscarProximoParaTriagem(Instant agora);

    /**
     * O pendente mais atrasado cuja nova tentativa automatica ja chegou, ja
     * travado para esta transacao. Mesma tecnica da fila de triagem: o
     * {@code SKIP LOCKED} e a reserva gravada no chamado.
     */
    @Query(value = """
            SELECT * FROM chamados
            WHERE status = 'PENDENTE_TRIAGEM_MANUAL'
              AND proxima_tentativa_em <= :agora
              AND (reservado_ate IS NULL OR reservado_ate < :agora)
            ORDER BY proxima_tentativa_em
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<Chamado> buscarProximoParaRevisao(Instant agora);

    long countByStatus(StatusChamado status);

    /** Pendentes que o revisor ainda vai retomar. */
    long countByStatusAndProximaTentativaEmIsNotNull(StatusChamado status);

    /** Pendentes que esgotaram as tentativas automaticas e esperam uma pessoa. */
    long countByStatusAndProximaTentativaEmIsNull(StatusChamado status);

    /** Le o chamado travando a linha, para gravar o resultado sem corrida. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Chamado c WHERE c.id = :id")
    Optional<Chamado> buscarParaAtualizar(UUID id);
}
