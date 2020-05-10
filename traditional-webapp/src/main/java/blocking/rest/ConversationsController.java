package blocking.rest;

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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.hateoas.RepresentationModel;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import blocking.repository.ConversationRepository;
import blocking.repository.UserRepository;
import domain.Conversation;
import domain.Message;

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
    public ResponseEntity<ConversationDto> getConversation(@PathVariable final String conversationId) {
        final var conversation = getRepository().findConversation(UUID.fromString(conversationId));
        if (conversation == null) {
            return ResponseEntity.notFound().build();
        }
        final var dto = new ConversationDto();
        dto.setId(conversation.getId().toString());
        dto.add(linkTo(methodOn(getClass()).getMessages(conversationId, null, null)).withRel("messages").toMono().toFuture().join());
        return ResponseEntity.ok(dto);
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
    public ResponseEntity<Message> getMessage(@PathVariable final String conversationId,
            @PathVariable final int id) {
        // TODO DTO
        // TODO link to conversation
        final var message = getRepository().findMessage(UUID.fromString(conversationId), id);
        return ResponseEntity.ok(message);
    }

    @GetMapping("/{conversationId}/messages")
    public ResponseEntity<MessageListDto> getMessages(@PathVariable final String conversationId,
            @RequestParam(defaultValue = "8") Integer limit, @RequestParam(required = false) final String cursor) {
        if (limit == null || limit < 0) {
            limit = 8;
        } else if (limit > 16) {
            limit = 16;
        }
        final Conversation conversation = getRepository().findConversation(UUID.fromString(conversationId));
        if (conversation == null) {
            return ResponseEntity.notFound().build();
        }

        final var messageIndex = getMessageIndex(cursor);
        if (messageIndex == null) {
            return ResponseEntity.badRequest().build();
        }

        final var messages = getRepository().findMessages(conversation, limit, messageIndex);
        final var dto = new MessageListDto();
        dto.setMessages(messages);

        // add links
        if (!messages.isEmpty()) {
            final var first = messages.get(0);
            if (first.getId() > Integer.MIN_VALUE) {
                // there *may* be a previous page
                dto.add(linkTo(methodOn(ConversationsController.class).getMessages(conversationId, limit,
                        createCursor(first.getId() - 1))).withRel("previous").toMono().toFuture().join());
            }
        }
        if (messageIndex >= conversation.getNextMessageId()) {
            dto.add(linkTo(methodOn(ConversationsController.class).getMessages(conversationId, limit,
                    createCursor(messageIndex + limit))).withRel("next").toMono().toFuture().join());
        }
        dto.add(linkTo(methodOn(getClass()).getConversation(conversationId)).withRel("conversation").toMono().toFuture().join());
        return ResponseEntity.ok(dto);
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

    public ResponseEntity<Void> sendMessage(final String fromId, final String toId, final String body) {
        final var sender = getUserRepository().findById(UUID.fromString(fromId));
        final var recipient = getUserRepository().findById(UUID.fromString(toId));
        final Conversation conversation = getRepository().findOrCreateConversation(sender, recipient);

        // in the future, we can use the sender's time zone
        var message = new Message(conversation.getId(), UUID.fromString(fromId), OffsetDateTime.now(getClock()), body);
        message = getRepository().createMessage(conversation, message);
        final var link = linkTo(methodOn(getClass()).getMessage(conversation.getId().toString(), message.getId()))
                .withRel("self").toMono().toFuture().join();
        return ResponseEntity.created(link.toUri()).build();
    }

    public ResponseEntity<Message> getMessage(final String senderId, final String recipientId, final int id) {
        final var sender = getUserRepository().findById(UUID.fromString(senderId));
        final var recipient = getUserRepository().findById(UUID.fromString(recipientId));
        final var message = getRepository().findMessage(sender, recipient, id);
        if (message == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(message);
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
        if( components.length != 2 || !"id".equalsIgnoreCase(components[ 0 ] ) ) {
            // invalid cursor syntax
            return null;
        }
        try {
            return Integer.parseInt(components[1]);
        } catch( final NumberFormatException nfe ) {
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