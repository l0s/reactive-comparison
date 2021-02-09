library( dplyr )
library( ggplot2 )
library( scales )
library( ggthemes )

scale_colour_discrete <- scale_colour_colorblind

latency <-
  read.csv( 'latency_vs_throughput.tsv',
            sep='\t',
            header=FALSE,
            col.names=c( 'paradigm', 'operation', 'offered_throughput', 'latency' ) )

aggregate <- latency %>%
  group_by( paradigm, operation, offered_throughput ) %>%
  summarise( latency_quantile=quantile( latency, probs=0.99 ) )

send_aggregate <- aggregate %>% filter( operation=='SND' )

ggplot( data=send_aggregate ) +
  geom_line( mapping=aes( x=offered_throughput,
                          y=latency_quantile,
                          group=paradigm,
                          color = paradigm ),
             size=2 ) +
  ggtitle( 'Message Sending' ) +
  xlab( 'Offered Throughput (requests per second)' ) +
  ylab( '99th Percentile Latency (milliseconds)' )

retrieval_aggregate <- aggregate %>% filter( operation=='RET' )

ggplot( data=retrieval_aggregate ) +
  geom_line( mapping=aes( x=offered_throughput,
                          y=latency_quantile,
                          group=paradigm,
                          color = paradigm ),
             size=2 ) +
  ggtitle( 'Message Retrieval' ) +
  xlab( 'Offered Throughput (requests per second)' ) +
  ylab( '99th Percentile Latency (milliseconds)' )