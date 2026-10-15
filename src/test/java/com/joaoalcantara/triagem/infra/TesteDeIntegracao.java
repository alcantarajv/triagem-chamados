package com.joaoalcantara.triagem.infra;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Marca um teste de integracao: sobe a aplicacao inteira contra um PostgreSQL de
 * verdade.
 *
 * <p>Anotacao composta para que nenhuma classe de teste repita a configuracao
 * dos containers — e para que acrescentar o WireMock no papel dos provedores,
 * na Etapa 3, seja uma edicao em um lugar so.</p>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest
@Import(PostgresDeTeste.class)
public @interface TesteDeIntegracao {
}
