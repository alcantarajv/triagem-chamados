package com.joaoalcantara.triagem.provedor;

import org.springframework.ai.chat.client.AdvisorParams;
import org.springframework.ai.chat.client.ChatClient;
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

    private final String nome;
    private final ChatClient cliente;

    public ProvedorCompativelComOpenAi(String nome, PropriedadesDoProvedor propriedades) {
        this.nome = nome;

        OpenAiChatOptions opcoes = OpenAiChatOptions.builder()
                .baseUrl(propriedades.url())
                // Vazia, e nao nula: com null o Spring AI procura OPENAI_API_KEY
                // no ambiente — a chave de outro provedor. Vazia, o cliente e
                // montado sem autenticacao e o provedor recusa a chamada: o
                // contexto sobe sem chave, que e o que o CI precisa.
                .apiKey(propriedades.temChave() ? propriedades.chave() : "")
                .model(propriedades.modelo())
                .timeout(propriedades.tempoLimite())
                // O SDK da OpenAI repete sozinho ate 3 vezes por padrao, sem que a
                // aplicacao fique sabendo. Desligado: quantas vezes repetir, e
                // diante de qual erro, e decisao desta aplicacao (Etapa 5), nao
                // da biblioteca. Repeticao invisivel e o defeito 3 do problema
                // central.
                .maxRetries(0)
                .build();

        this.cliente = ChatClient.create(OpenAiChatModel.builder().options(opcoes).build());
    }

    @Override
    public String nome() {
        return nome;
    }

    /**
     * Pede a classificacao com saida estruturada nativa: o esquema JSON de
     * {@link Classificacao} vai no campo {@code response_format} da requisicao,
     * e o provedor restringe a geracao a ele.
     *
     * <p>A alternativa — so descrever o formato no texto do prompt — funciona em
     * qualquer modelo, mas deixa o formato a cargo da boa vontade do modelo.
     * Nenhuma das duas dispensa validar a resposta.</p>
     */
    @Override
    public Classificacao classificar(String texto) {
        try {
            return cliente.prompt()
                    .advisors(AdvisorParams.ENABLE_NATIVE_STRUCTURED_OUTPUT)
                    .system(InstrucoesDeTriagem.SISTEMA)
                    .user(InstrucoesDeTriagem.mensagemDoUsuario(texto))
                    .call()
                    .entity(Classificacao.class);
        } catch (RuntimeException e) {
            throw new FalhaNoProvedorException(nome, e);
        }
    }
}
