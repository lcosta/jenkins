FROM postgres:latest

COPY include/create_developer.sql /docker-entrypoint-initdb.d/init.sql

EXPOSE 5432

CMD ["postgres"]