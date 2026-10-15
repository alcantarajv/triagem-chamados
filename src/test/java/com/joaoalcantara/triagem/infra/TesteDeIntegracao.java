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
 * <p>Anotacao composta para que nenhuma classe de teste repita essa
 * configuracao — e para que muda-la seja uma edicao em um lugar so.</p>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(properties = "triagem.worker.habilitado=false")
@Import({PostgresDeTeste.class, ProvedorFalso.class, RelogioAjustavel.class})
public @interface TesteDeIntegracao {
}
