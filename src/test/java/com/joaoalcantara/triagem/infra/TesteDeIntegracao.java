package com.joaoalcantara.triagem.infra;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Marca um teste de integracao: sobe a aplicacao inteira contra um PostgreSQL de
 * verdade, com o WireMock no lugar do provedor de IA e um relogio que o teste
 * controla.
 *
 * <p>O agendamento do worker fica desligado: cada teste aciona o triador no
 * momento exato, em vez de competir com uma execucao em segundo plano que
 * poderia pegar o chamado antes da hora.</p>
 *
 * <p>Tempo limite e esperas encolhidos: os testes de lentidao e de nova
 * tentativa esperam de verdade, e com os valores de producao (15s, 1s, 10s) a
 * suite levaria minutos. As proporcoes entre eles sao as mesmas.</p>
 *
 * <p>Anotacao composta para que nenhuma classe de teste repita essa
 * configuracao — e para que muda-la seja uma edicao em um lugar so.</p>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(properties = {
        "triagem.worker.habilitado=false",
        "triagem.provedores.gemini.tempo-limite=1s",
        "triagem.provedores.groq.tempo-limite=1s",
        "triagem.repeticao.espera-inicial=100ms",
        "triagem.repeticao.espera-maxima=2s"})
@Import({PostgresDeTeste.class, ProvedorFalso.class, RelogioAjustavel.class, AmbienteDeTeste.class})
public @interface TesteDeIntegracao {
}
