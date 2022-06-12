create table github_oauth
(
    discord_id unsigned big int not null primary key,
    token      text not null
);

create table pull_requests
(
    repo        text not null,
    number      unsigned big int not null,
    thread      unsigned big int not null,
    comments    int,
    labels      text,
    title       text,
    description text,
    state       text,
    commits     int,
    constraint pk_prs primary key (repo, number)
);

create table components
(
    feature   text      not null,
    id        text      not null,
    arguments text      not null,
    lifespan  text      not null,
    last_used timestamp not null,
    constraint pk_components primary key (feature, id)
);

create table mods
(
    fork       text not null,
    project_id int  not null primary key,
    issue      int  not null
);