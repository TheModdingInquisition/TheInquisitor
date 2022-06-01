create table github_oauth
(
    discord_id unsigned big int not null primary key,
    token      text not null
);