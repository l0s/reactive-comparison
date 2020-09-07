library( dplyr )
library( ggplot2 )
library( scales )

latency <-
  read.csv( 'latency.tsv',
            sep='\t',
            header=FALSE,
            col.names=c( 'paradigm', 'metric', 'milliseconds' ) )
throughput <-
  read.csv( 'throughput.tsv',
            sep='\t',
            header=FALSE,
            col.names=c( 'paradigm', 'metric', 'requests', 'milliseconds' ) )
throughput$rps = throughput$requests / ( throughput$milliseconds / 1000 )

# Throughput

message_sending_throughput <- throughput %>% filter( metric=='SENT_MESSAGES' )
ggplot( message_sending_throughput,
        aes( x=paradigm, y=rps ) ) +
  geom_segment( aes( x=paradigm,
                     xend=paradigm, y=0, yend=rps)) +
  geom_point( size=5, color="red", fill=alpha("orange", 0.3), alpha=0.7, shape=21, stroke=2) +
  coord_flip() +
  ggtitle( 'Message Sending Throughput' ) +
  xlab( 'Paradigm' ) +
  ylab( 'Requests per second' )

retrieval_throughput <- throughput %>% filter( metric=='RETRIEVED_MESSAGES' )
ggplot( retrieval_throughput,
        aes( x=paradigm, y=rps ) ) +
  geom_segment( aes( x=paradigm,
                     xend=paradigm, y=0, yend=rps)) +
  geom_point( size=5, color="red", fill=alpha("orange", 0.3), alpha=0.7, shape=21, stroke=2) +
  coord_flip() +
  ggtitle( 'Message Retrieval Throughput' ) +
  xlab( 'Paradigm' ) +
  ylab( 'Requests per second' )

# Latency

create_single_user <- latency %>% filter( metric=='CREATE_SINGLE_USER' )
ggplot( create_single_user,
        aes( x=factor( paradigm ),
             y=milliseconds,
             fill=factor( paradigm ) ) ) +
  geom_violin( trim = FALSE ) +
  geom_boxplot( width=0.1 ) +
  scale_y_continuous( labels = number_format( accuracy = 1L,
                                              big.mark = ',',
                                              suffix = 'ms' ) ) +
  ggtitle( 'Create Single User Latency' ) +
  xlab( 'Paradigm' ) +
  ylab( 'Latency (milliseconds)' )

get_page_of_users <- latency %>% filter( metric=='GET_PAGE_OF_USERS' )
ggplot( get_page_of_users,
        aes( x=factor( paradigm ),
             y=milliseconds,
             fill=factor( paradigm ) ) ) +
  geom_violin( trim=FALSE ) +
  geom_boxplot( width=0.1 ) +
  scale_y_continuous( labels = number_format( accuracy = 1L,
                                              big.mark = ',',
                                              suffix = 'ms' ) ) +
  ggtitle( 'Fetch single page of users latency' ) +
  xlab( 'Paradigm' ) +
  ylab( 'Latency (milliseconds)' )

# Send and Receive messages
# sending a message prompts the recipient to retrieve a page of messages
# so sending and recieving occur concurrently

send_single_message <- latency %>% filter( metric=='SEND_SINGLE_MESSAGE' )
ggplot( send_single_message,
        aes( x=factor( paradigm ),
             y=milliseconds,
             fill=factor( paradigm ) ) ) +
  geom_violin( trim=TRUE ) +
  geom_boxplot( width=0.05 ) +
  scale_y_continuous( #trans='log',
                      labels = number_format( accuracy = 1L,
                                              big.mark = ',',
                                              suffix = 'ms' ) ) +
  ggtitle( 'Send single message latency' ) +
  xlab( 'Paradigm' ) +
  ylab( 'Latency (milliseconds)' )

get_messages_for_user <- latency %>% filter( metric=='GET_MESSAGES_FOR_USER' )
ggplot( get_messages_for_user,
        aes( x=factor( paradigm ),
             y=milliseconds,
             fill=factor( paradigm ) ) ) +
  geom_violin( trim=TRUE ) +
  geom_boxplot( width=0.05 ) +
  scale_y_continuous( #trans='log',
                      labels = number_format( accuracy = 1L,
                                              big.mark = ',',
                                              suffix = 'ms' ) ) +
  ggtitle( 'Get messages for user latency' ) +
  xlab( 'Paradigm' ) +
  ylab( 'Latency (milliseconds)' )
