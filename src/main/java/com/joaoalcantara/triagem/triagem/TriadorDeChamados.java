package com.joaoalcantara.triagem.triagem;

import java.time.Duration;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.joaoalcantara.triagem.chamado.ResultadoDaTriagem;
import com.joaoalcantara.triagem.infra.MetricasDaTriagem;
import com.joaoalcantara.triagem.triagem.ClassificadorComValidacao.TriagemObtida;
import com.joaoalcantara.triagem.triagem.FilaDeTriagem.ChamadoReservado;

/**
 * Tria um chamado por vez: reserva, obtem uma classificacao validada, grava o
 * resultado.
 *
 * <p>Sem {@code @Transactional} de proposito — ver {@link FilaDeTriagem}.</p>
 */
@Component
public class TriadorDeChamados {

    private static final Logger log = LoggerFactory.getLogger(TriadorDeChamados.class);

    private final FilaDeTriagem fila;
    private final ClassificadorComValidacao classificador;
    private final MetricasDaTriagem metricas;

    public TriadorDeChamados(FilaDeTriagem fila, ClassificadorComValidacao classificador, MetricasDaTriagem metricas) {
        this.fila = fila;
        this.classificador = classificador;
        this.metricas = metricas;
    }

    /**
     * Processa o proximo chamado da fila.
     *
     * @return {@code false} quando a fila estava vazia
     */
    public boolean processarProximo() {
        return processar(fila.reservarProximo());
    }

    /**
     * Revisa o proximo pendente cuja nova tentativa automatica ja chegou.
     *
     * @return {@code false} quando nenhum pendente estava na hora
     */
    public boolean processarProximoPendente() {
        return processar(fila.reservarProximoPendente());
    }

    private boolean processar(Optional<ChamadoReservado> reservado) {
        if (reservado.isEmpty()) {
            return false;
        }
        triar(reservado.get());
        return true;
    }

    /**
     * Qualquer falha deixa o chamado pendente, e nunca o devolve a fila de
     * {@code RECEBIDO}. Devolve-lo parece mais generoso, mas um chamado que
     * sempre falha — texto que o modelo nao consegue classificar, cota do dia
     * esgotada — seria pego de novo a cada volta do worker, para sempre,
     * gastando cota a cada tentativa. A segunda chance existe, mas agendada e
     * com espera crescente: e o revisor de pendentes que a da.
     *
     * <p>Chegar aqui com falha ja significa muito: cada provedor da cadeia teve
     * as suas novas tentativas e a correcao de resposta invalida.</p>
     *
     * <p>O texto do chamado nunca vai para o log: pode conter dados pessoais.</p>
     */
    private void triar(ChamadoReservado chamado) {
        long inicio = System.nanoTime();
        try {
            TriagemObtida triagem = classificador.classificar(chamado.texto());
            Duration duracao = Duration.ofNanos(System.nanoTime() - inicio);
            fila.registrarTriagem(chamado.id(), new ResultadoDaTriagem(triagem.classificacao(), triagem.provedor(),
                    triagem.doCache(), triagem.provedoresQueFalharam(), duracao));
            metricas.triagem("triado", triagem.provedor(), triagem.houveFallback(), triagem.doCache(), duracao);
            if (triagem.doCache()) {
                log.info("Chamado {} triado pelo cache (classificacao original de {})", chamado.id(),
                        triagem.provedor());
            } else if (triagem.houveFallback()) {
                log.info("Chamado {} triado por {}, depois de falha em {}", chamado.id(), triagem.provedor(),
                        triagem.provedoresQueFalharam());
            } else {
                log.info("Chamado {} triado por {}", chamado.id(), triagem.provedor());
            }
        } catch (TodosOsProvedoresFalharamException e) {
            log.warn("Chamado {} fica pendente: {}", chamado.id(), e.getMessage());
            fila.registrarFalhaDaTriagem(chamado.id());
            registrarPendente(inicio);
        } catch (RuntimeException e) {
            // Classificacao validada que mesmo assim nao coube no banco. Nao deveria
            // acontecer — o validador e as restricoes do banco usam os mesmos
            // limites —, mas, se acontecer, o chamado nao pode ficar preso na fila.
            log.error("Chamado {} fica pendente: falha ao gravar a triagem", chamado.id(), e);
            fila.registrarFalhaDaTriagem(chamado.id());
            registrarPendente(inicio);
        }
    }

    private void registrarPendente(long inicio) {
        metricas.triagem("pendente", "nenhum", false, false, Duration.ofNanos(System.nanoTime() - inicio));
    }
}
