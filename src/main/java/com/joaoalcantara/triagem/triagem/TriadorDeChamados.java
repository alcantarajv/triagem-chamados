package com.joaoalcantara.triagem.triagem;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.joaoalcantara.triagem.provedor.FalhaNoProvedorException;
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

    public TriadorDeChamados(FilaDeTriagem fila, ClassificadorComValidacao classificador) {
        this.fila = fila;
        this.classificador = classificador;
    }

    /**
     * Processa o proximo chamado da fila.
     *
     * @return {@code false} quando a fila estava vazia
     */
    public boolean processarProximo() {
        Optional<ChamadoReservado> reservado = fila.reservarProximo();
        if (reservado.isEmpty()) {
            return false;
        }
        triar(reservado.get());
        return true;
    }

    /**
     * Qualquer falha manda o chamado para triagem manual, e nunca o deixa
     * {@code RECEBIDO}. Deixa-lo na fila parece mais generoso, mas um chamado
     * que sempre falha — texto que o modelo nao consegue classificar, chave
     * invalida — seria pego de novo a cada vencimento da reserva, para sempre,
     * gastando cota a cada volta.
     *
     * <p>A repeticao de erros transitorios (Etapa 5), o segundo provedor
     * (Etapa 6) e o job que revisita os pendentes (Etapa 7) entram aqui, um por
     * vez.</p>
     *
     * <p>O texto do chamado nunca vai para o log: pode conter dados pessoais.</p>
     */
    private void triar(ChamadoReservado chamado) {
        try {
            TriagemObtida triagem = classificador.classificar(chamado.texto());
            fila.registrarTriagem(chamado.id(), triagem.classificacao(), triagem.provedor());
            log.info("Chamado {} triado por {}", chamado.id(), triagem.provedor());
        } catch (FalhaNoProvedorException | RespostaInvalidaException e) {
            log.warn("Chamado {} vai para triagem manual: {}", chamado.id(), e.getMessage());
            fila.encaminharParaTriagemManual(chamado.id());
        } catch (RuntimeException e) {
            // Classificacao validada que mesmo assim nao coube no banco. Nao deveria
            // acontecer — o validador e as restricoes do banco usam os mesmos
            // limites —, mas, se acontecer, o chamado nao pode ficar preso na fila.
            log.error("Chamado {} vai para triagem manual: falha ao gravar a triagem", chamado.id(), e);
            fila.encaminharParaTriagemManual(chamado.id());
        }
    }
}
