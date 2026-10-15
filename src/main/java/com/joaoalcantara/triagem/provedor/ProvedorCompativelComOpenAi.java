package com.joaoalcantara.triagem.provedor;

import java.time.Clock;
import java.util.List;
import java.util.function.Supplier;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

import com.joaoalcantara.triagem.chamado.Classificacao;

/**
 * Provedor de IA que fala a API de chat da OpenAI.
 *
 * <p>Gemini e Groq expoem, os dois, um endpoint compativel com essa API. Uma
 * unica classe, instanciada uma vez por provedor com URL, chave e modelo
 * proprios, atende aos dois — e o WireMock dos testes so precisa imitar um
 * formato de resposta.</p>
 */
public class ProvedorCompativelComOpenAi implements ProvedorDeIa {

    /**
     * Esquema JSON de {@link Classificacao}, enviado em toda requisicao no campo
     * {@code response_format}: saida estruturada nativa, em que o provedor
     * restringe a geracao ao esquema.
     *
     * <p>A alternativa — so descrever o formato no texto do prompt — funciona em
     * qualquer modelo, mas deixa o formato a cargo da boa vontade do modelo.
     * Nenhuma das duas dispensa validar a resposta.</p>
     */
    private static final String ESQUEMA = new BeanOutputConverter<>(Classificacao.class).getJsonSchema();

    private final String nome;
    private final ChatClient cliente;
    private final Clock relogio;

    public ProvedorCompativelComOpenAi(String nome, PropriedadesDoProvedor propriedades, Clock relogio) {
        this.nome = nome;
        this.relogio = relogio;

        OpenAiChatOptions opcoes = OpenAiChatOptions.builder()
                .baseUrl(propriedades.url())
                // Vazia, e nao nula: com null o Spring AI procura OPENAI_API_KEY
                // no ambiente — a chave de outro provedor. Vazia, o cliente e
                // montado sem autenticacao e o provedor recusa a chamada: o
                // contexto sobe sem chave, que e o que o CI precisa.
                .apiKey(propriedades.temChave() ? propriedades.chave() : "")
                .model(propriedades.modelo())
                // Tempo limite da chamada inteira, nao so da conexao: um provedor
                // que aceita a conexao e demora a responder e justamente o caso
                // que importa. O padrao do SDK e 60 segundos.
                .timeout(propriedades.tempoLimite())
                // O SDK da OpenAI repete sozinho ate 3 vezes por padrao, sem que a
                // aplicacao fique sabendo. Desligado: quantas vezes repetir, e
                // diante de qual erro, e decisao da aplicacao (ProvedorComRepeticao),
                // nao da biblioteca. Com as duas ligadas, as tentativas se
                // multiplicariam: 3 nossas x 4 do SDK = 12 requisicoes por falha.
                .maxRetries(0)
                .outputSchema(ESQUEMA)
                .build();

        this.cliente = ChatClient.create(OpenAiChatModel.builder().options(opcoes).build());
    }

    @Override
    public String nome() {
        return nome;
    }

    /**
     * Devolve o texto da resposta, sem converter: com {@code entity(...)}, uma
     * categoria inventada viraria uma excecao do Jackson, indistinguivel de
     * qualquer outra falha — e a triagem precisa saber exatamente o que estava
     * errado para pedir a correcao.
     */
    @Override
    public String classificar(String texto) {
        return chamar(() -> cliente.prompt()
                .system(InstrucoesDeTriagem.SISTEMA)
                .user(InstrucoesDeTriagem.mensagemDoUsuario(texto))
                .call()
                .content());
    }

    /**
     * Continua a conversa: o pedido original, a resposta recusada como fala do
     * proprio modelo, e os motivos da recusa. Ver o que respondeu e por que nao
     * serviu da ao modelo mais chance de acertar do que repetir o pedido do zero.
     */
    @Override
    public String corrigir(String texto, String respostaAnterior, List<String> erros) {
        return chamar(() -> cliente.prompt()
                .system(InstrucoesDeTriagem.SISTEMA)
                .messages(
                        new UserMessage(InstrucoesDeTriagem.mensagemDoUsuario(texto)),
                        new AssistantMessage(InstrucoesDeTriagem.respostaAnterior(respostaAnterior)),
                        new UserMessage(InstrucoesDeTriagem.mensagemDeCorrecao(erros)))
                .call()
                .content());
    }

    private String chamar(Supplier<String> chamada) {
        try {
            return chamada.get();
        } catch (RuntimeException e) {
            throw TradutorDeFalhas.traduzir(nome, e, relogio);
        }
    }
}
