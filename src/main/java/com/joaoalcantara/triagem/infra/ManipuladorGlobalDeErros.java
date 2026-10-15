package com.joaoalcantara.triagem.infra;

import java.net.URI;
import java.time.Clock;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.joaoalcantara.triagem.chamado.ChamadoNaoEncontradoException;
import com.joaoalcantara.triagem.custo.LimiteDeChamadosExcedidoException;

/**
 * Traduz excecoes em respostas no formato Problem Details (RFC 9457).
 *
 * <p>A API nunca devolve stack trace nem mensagem de driver de banco: cada erro
 * vira um documento {@code application/problem+json} com um {@code type}
 * estavel, que o cliente pode tratar programaticamente.</p>
 */
@RestControllerAdvice
public class ManipuladorGlobalDeErros extends ResponseEntityExceptionHandler {

    // Logger proprio: o "logger" herdado de ResponseEntityExceptionHandler e o
    // Commons Logging, com assinaturas diferentes das do SLF4J.
    private static final Logger log = LoggerFactory.getLogger(ManipuladorGlobalDeErros.class);

    private static final String BASE_TIPO = "https://api.triagem.dev/erros/";

    private final Clock relogio;

    public ManipuladorGlobalDeErros(Clock relogio) {
        this.relogio = relogio;
    }

    @ExceptionHandler(ChamadoNaoEncontradoException.class)
    public ProblemDetail trataChamadoNaoEncontrado(ChamadoNaoEncontradoException e) {
        return problema(HttpStatus.NOT_FOUND, "chamado-nao-encontrado", "Chamado nao encontrado", e.getMessage());
    }

    /**
     * Limite de chamados por cliente atingido: 429, com {@code Retry-After}.
     *
     * <p>O mesmo cabecalho que esta aplicacao respeita quando o provedor de IA o
     * manda: diz ao cliente quando a janela vira, em vez de deixa-lo tentar a
     * esmo ate passar.</p>
     */
    @ExceptionHandler(LimiteDeChamadosExcedidoException.class)
    public ResponseEntity<ProblemDetail> trataLimiteExcedido(LimiteDeChamadosExcedidoException e) {
        long segundos = Math.max(1, e.esperar().toSeconds());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(segundos))
                .body(problema(HttpStatus.TOO_MANY_REQUESTS, "limite-de-chamados", "Limite de chamados atingido",
                        e.getMessage()));
    }

    /**
     * Qualquer erro nao previsto: 500, mas ainda em Problem Details.
     *
     * <p>Sem este tratamento, a excecao cairia na pagina de erro padrao do
     * Spring Boot, num formato diferente de todas as outras respostas de erro.
     * O detalhe e generico de proposito: a mensagem real pode conter SQL, nome
     * de tabela ou trecho do chamado, e vai so para o log.</p>
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail trataErroInesperado(Exception e) {
        log.error("Erro inesperado ao processar a requisicao", e);
        return problema(HttpStatus.INTERNAL_SERVER_ERROR, "erro-interno",
                "Erro interno", "Ocorreu um erro inesperado. Tente novamente mais tarde.");
    }

    /** Falhas de Bean Validation no corpo: 400 com a lista de campos. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException e, HttpHeaders headers, HttpStatusCode status, WebRequest request) {

        List<String> erros = e.getBindingResult().getFieldErrors().stream()
                .map(erro -> "%s: %s".formatted(erro.getField(), erro.getDefaultMessage()))
                .sorted()
                .toList();

        ProblemDetail corpo = problema(HttpStatus.BAD_REQUEST, "requisicao-invalida",
                "Requisicao invalida", "Um ou mais campos estao invalidos");
        corpo.setProperty("erros", erros);
        return ResponseEntity.badRequest().body(corpo);
    }

    /**
     * Corpo que nao e JSON, ou JSON com tipo errado: 400.
     *
     * <p>A mensagem do Jackson fica no log e nao na resposta: ela cita classes
     * internas da aplicacao, o que nao ajuda o cliente e ensina a quem quer
     * atacar como o codigo e organizado.</p>
     */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException e, HttpHeaders headers, HttpStatusCode status, WebRequest request) {

        log.debug("Corpo ilegivel: {}", e.getMessage());
        return ResponseEntity.badRequest().body(problema(HttpStatus.BAD_REQUEST, "corpo-ilegivel",
                "Corpo ilegivel", "O corpo da requisicao nao e um JSON valido para esta operacao"));
    }

    /** Parametro de caminho em formato errado, como um id que nao e UUID: 400. */
    @Override
    protected ResponseEntity<Object> handleTypeMismatch(
            TypeMismatchException e, HttpHeaders headers, HttpStatusCode status, WebRequest request) {

        String detalhe = "O valor '%s' nao e valido para o parametro '%s'".formatted(e.getValue(), e.getPropertyName());
        return ResponseEntity.badRequest().body(problema(HttpStatus.BAD_REQUEST, "parametro-invalido",
                "Parametro invalido", detalhe));
    }

    private ProblemDetail problema(HttpStatus status, String codigo, String titulo, String detalhe) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(status, detalhe);
        problema.setType(URI.create(BASE_TIPO + codigo));
        problema.setTitle(titulo);
        problema.setProperty("ocorridoEm", relogio.instant());
        return problema;
    }
}
