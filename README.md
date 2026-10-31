# Capri Chip

Assistente de voz para Android com a Capri flutuando sobre os outros apps.
Projeto de fã, não oficial, inspirado em Cyberpunk: Edgerunners 2.

- O boneco fica sobre qualquer app. Toque nele para digitar, arraste para mover, segure para ligar ou desligar a escuta.
- Diga "Ok Capri" e fale o pedido (na mesma frase ou depois do bip). A resposta aparece na caixa de diálogo e é falada em voz alta.
- A imagem da personagem é sua: escolha uma no app e recorte o fundo com a varinha ou o pincel. O app não traz nenhuma imagem dela.

## Compilar

O repositório compila sozinho no GitHub Actions (aba Actions, "Compilar APK"). Ao terminar, baixe o artefato `capri-chip-apk`, descompacte e instale o `app-debug.apk`.

Local: JDK 17, Android SDK 35 e `./gradlew assembleDebug`.

## Instalar e usar

1. Instale o APK (permita "instalar apps desconhecidos" para o navegador ou gerenciador de arquivos).
2. Abra o app e toque em "Conceder permissões": microfone, notificações e "sobrepor outros apps".
3. Cole uma chave de API. A grátis é a do Google: entre em aistudio.google.com com sua conta Google, toque em Get API key e copie (começa com AIza, não pede cartão). Chaves da Anthropic (sk-ant-) também funcionam, mas são pagas. A chave fica só no celular.
4. Escolha a imagem da Capri e recorte o fundo.
5. Toque em "Ativar Capri".

## Avisos

- A escuta de "Ok Capri" usa o reconhecimento de voz do Google em repetição. Gasta bateria, mantém o ícone de microfone aceso e, se o pacote de voz offline do português não estiver instalado, o áudio pode ir aos servidores do Google. Alguns celulares tocam um bip a cada ciclo.
- As conversas vão direto do celular para o Google (chave AIza) ou para a Anthropic (chave sk-ant-). No plano grátis do Google, o conteúdo pode ser usado para melhorar os produtos dele, e os limites diários podem mudar. Com a chave da Anthropic, as mensagens consomem créditos.
- Depois de reiniciar o celular, abra o app e ative de novo.
- Ainda não foi testado em aparelho real.
