package com.joaoalcantara.triagem.triagem;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.joaoalcantara.triagem.chamado.ChamadoRepositorio;
import com.joaoalcantara.triagem.chamado.ChamadoServico;
import com.joaoalcantara.triagem.infra.AmbienteDeTeste;
import com.joaoalcantara.triagem.infra.RelogioAjustavel.ClockMovel;
import com.joaoalcantara.triagem.infra.TesteDeIntegracao;
import com.joaoalcantara.triagem.triagem.FilaDeTriagem.ChamadoReservado;

/** A reserva de chamados: quem pega o que, e quando um chamado volta para a fila. */
@TesteDeIntegracao
class FilaDeTriagemIT {

    @Autowired
    private AmbienteDeTeste ambiente;

    @Autowired
    private FilaDeTriagem fila;

    @Autowired
    private ChamadoServico servico;

    @Autowired
    private ChamadoRepositorio repositorio;

    @Autowired
    private PropriedadesDoWorker propriedades;

    @Autowired
    private ClockMovel relogio;

    @BeforeEach
    void limpar() {
        ambiente.limpar();
    }

    @Test
    @DisplayName("entrega o chamado mais antigo primeiro")
    void maisAntigoPrimeiro() {
        UUID antigo = servico.registrar("Primeiro chamado").getId();
        relogio.avancar(Duration.ofSeconds(1));
        servico.registrar("Segundo chamado");

        assertThat(fila.reservarProximo()).map(ChamadoReservado::id).contains(antigo);
    }

    @Test
    @DisplayName("chamado reservado nao e entregue a outro worker enquanto a reserva vale")
    void reservadoNaoEEntregueDeNovo() {
        UUID id = servico.registrar("Chamado unico").getId();

        assertThat(fila.reservarProximo()).map(ChamadoReservado::id).contains(id);
        assertThat(fila.reservarProximo()).isEmpty();

        relogio.avancar(propriedades.reserva().minusSeconds(1));
        assertThat(fila.reservarProximo()).isEmpty();
    }

    @Test
    @DisplayName("reserva vencida devolve o chamado a fila: worker que morreu nao prende chamado")
    void reservaVencidaVoltaParaAFila() {
        UUID id = servico.registrar("Chamado de um worker que morreu").getId();
        fila.reservarProximo();

        relogio.avancar(propriedades.reserva().plusSeconds(1));

        assertThat(fila.reservarProximo()).map(ChamadoReservado::id).contains(id);
    }
}
