package reactive.rest;

import static org.springframework.hateoas.server.reactive.WebFluxLinkBuilder.linkTo;
import static org.springframework.hateoas.server.reactive.WebFluxLinkBuilder.methodOn;

import java.nio.charset.Charset;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Base64.Decoder;
import java.util.Base64.Encoder;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.hateoas.RepresentationModel;
import org.springframework.hateoas.server.reactive.WebFluxLinkBuilder.WebFluxLink;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.ResponseEntity.BodyBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import domain.Conversation;
import domain.Message;
import reactive.repository.ConversationRepository;
import reactive.repository.UserRepository;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SynchronousSink;

@RestController
@RequestMapping("/conversations")
public class ConversationsController {

    private static final Encoder encoder = Base64.getUrlEncoder();
    private static final Decoder decoder = Base64.getUrlDecoder();
    private static final Charset charset = Charset.forName("UTF-8");

    private final Logger logger = LoggerFactory.getLogger(getClass());

    private final Clock clock;
    private final ConversationRepository repository;
    private final UserRepository userRepository;

    @Autowired
    public ConversationsController(final Clock clock, final ConversationRepository repository,
            final UserRepository userRepository) {
        Objects.requireNonNull(clock);
        Objects.requireNonNull(repository);
        Objects.requireNonNull(userRepository);
        this.clock = clock;
        this.repository = repository;
        this.userRepository = userRepository;
    }

    @GetMapping("/{conversationId}")
    public Mono<ResponseEntity<ConversationDto>> getConversation(@PathVariable final String conversationId) {
        // FIXME add link to messages
        return getRepository().findConversation(UUID.fromString(conversationId))
            .switchIfEmpty(Mono.just(null))
            .handle(this::emitDto)
            .map(ResponseEntity::ok)
            .defaultIfEmpty(ResponseEntity.status(HttpStatus.NOT_FOUND).body(null));
    }

    public static class ConversationDto extends RepresentationModel<ConversationDto> {
        private String id;

        public String getId() {
            return id;
        }

        public void setId(final String id) {
            this.id = id;
        }
    }

    @GetMapping("/{conversationId}/messages/{id}")
    public Mono<ResponseEntity<Message>> getMessage(@PathVariable final String conversationId,
            @PathVariable final int id) {
        return getRepository().findMessage(UUID.fromString(conversationId), id).map(ResponseEntity::ok);
    }

    @GetMapping("/{conversationId}/messages")
    public Mono<ResponseEntity<MessageListDto>> getMessages(@PathVariable final String conversationId,
            @RequestParam(defaultValue = "8") final Integer limit,
            @RequestParam(required = false) final String cursor) {
        if (limit == null || limit < 0) {
            return getMessages(conversationId, 8, cursor);
        } else if (limit > 16) {
            return getMessages(conversationId, 16, cursor);
        }

        final var messageIndex = getMessageIndex(cursor);
        if (messageIndex == null) {
            return Mono.just(ResponseEntity.badRequest().build());
        }

        // TODO ensure the user is party to the conversation

        final var emptyHandler = Mono.fromSupplier(() -> {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body((MessageListDto) null);
        });
        return getRepository().findConversation(UUID.fromString(conversationId)).flatMap(conversation -> {
            return getRepository().findMessages(Mono.just(conversation), limit, messageIndex)
                    .collectList()
                    .map(messages -> {
                        final var dto = new MessageListDto();
                        dto.messages = messages;
                        // add links
                        final var links = new LinkedBlockingQueue<WebFluxLink>();
                        if (!messages.isEmpty()) {
                            final var first = messages.get(0);
                            if (first.getId() > Integer.MIN_VALUE) {
                                // there *may* be a previous page
                                links.add(linkTo(methodOn(ConversationsController.class).getMessages(conversationId, limit,
                                        createCursor(first.getId() - 1))).withRel("previous"));
                            }
                        }
                        if (messageIndex >= conversation.getNextMessageId()) {
                            links.add(linkTo(methodOn(ConversationsController.class).getMessages(conversationId, limit,
                                    createCursor(messageIndex + limit))).withRel("next"));
                        }
                        links.add(linkTo(methodOn(getClass()).getConversation(conversationId)).withRel("conversation"));
                        links.stream().map(WebFluxLink::toMono).map(Mono::toFuture).map(CompletableFuture::join).forEach(dto::add);

                        return ResponseEntity.ok(dto);
                     });
        })
        .switchIfEmpty(emptyHandler);
    }

    public static class MessageListDto extends RepresentationModel<MessageListDto> {
        private List<Message> messages;

        public List<Message> getMessages() {
            return messages;
        }

        public void setMessages(final List<Message> messages) {
            Objects.requireNonNull(messages);
            this.messages = messages;
        }
    }

    public Mono<ResponseEntity<Void>> sendMessage(final String fromId, final String toId, final String body) {
        final var sender = getUserRepository().findById(UUID.fromString(fromId));
        final var recipient = getUserRepository().findById(UUID.fromString(toId));
        final var conversation = getRepository().findOrCreateConversation(sender, recipient).cache();
        final var message = conversation.map(c -> {
            // in the future, we can use the sender's time zone
            return new Message(c.getId(), UUID.fromString(fromId),
                    OffsetDateTime.now(getClock()), body);
        });

        return getRepository().createMessage(conversation, message)
        .flatMap(m -> {
            return linkTo(
                    methodOn(getClass()).getMessage(m.getConversationId().toString(), m.getId()))
                            .withRel("self").toMono();
        })
        .map(link -> {
            final var locationUri = link.toUri();
            return ResponseEntity.status(HttpStatus.CREATED).header("Location", locationUri.toString());
        })
        .switchIfEmpty(Mono.fromSupplier(() -> {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR);
        }))
        .map(BodyBuilder::build);
    }

    public Mono<ResponseEntity<Message>> getMessage(final String senderId, final String recipientId, final int id) {
        final var sender = getUserRepository().findById(UUID.fromString(senderId));
        final var recipient = getUserRepository().findById(UUID.fromString(recipientId));
        final var message = getRepository().findMessage(sender, recipient, id);
        return message.map(ResponseEntity::ok)
                .switchIfEmpty(Mono.just(ResponseEntity.status(HttpStatus.NOT_FOUND).body((Message)null)));
    }

    protected void emitDto(final Conversation conversation, final SynchronousSink<ConversationDto> sink) {
        final var dto = new ConversationDto();
        dto.setId(conversation.getId().toString());
        final var webfluxLink =
                linkTo(methodOn(getClass()).getMessages(conversation.getId().toString(), null, null))
                .withRel("messages");
        webfluxLink.toMono().doOnSuccess(link -> {
            dto.add(link);
            sink.next(dto);
        });
    }

    protected String createCursor(final int messageIndex) {
        final String string = "id:" + messageIndex;
        return encoder.encodeToString(string.getBytes(charset));
    }

    protected Integer getMessageIndex(final String cursor) {
        if (cursor == null || cursor.isEmpty() || cursor.isBlank()) {
            // no cursor is provided, get the latest messages
            return Integer.MAX_VALUE;
        }
        final var bytes = decoder.decode(cursor.strip());
        final var string = new String(bytes, charset);
        final var components = string.split(":", 1);
        if (components.length != 2 || !"id".equalsIgnoreCase(components[0])) {
            // invalid cursor syntax
            return null;
        }
        try {
            return Integer.parseInt(components[1]);
        } catch (final NumberFormatException nfe) {
            // invalid cursor syntax
            logger.debug("Invalid message cursor: " + cursor + ": " + nfe.getMessage(), nfe);
            return null;
        }
    }

    protected ConversationRepository getRepository() {
        return repository;
    }

    protected Clock getClock() {
        return clock;
    }

    protected UserRepository getUserRepository() {
        return userRepository;
    }

}