create table pull_requests
(
    repo   text not null,
    number unsigned big int not null,
    thread unsigned big int not null,
    constraint pk_prs primary key (repo, number)
);